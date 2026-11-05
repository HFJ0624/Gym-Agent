package com.sau.gym.admin.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.context.AgentContextBudgeter;
import com.sau.gym.admin.agent.context.TokenEstimator;
import com.sau.gym.admin.agent.harness.interceptor.ModelCallRequest;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationChain;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.ToolCallRequest;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationChain;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationInterceptor;
import com.sau.gym.admin.agent.llm.LlmChatResponse;
import com.sau.gym.admin.agent.llm.LlmClient;
import com.sau.gym.admin.agent.llm.LlmException;
import com.sau.gym.admin.agent.llm.LlmMessage;
import com.sau.gym.admin.agent.llm.LlmToolCall;
import com.sau.gym.admin.agent.llm.LlmToolDefinition;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.tool.AgentToolSchemaBuilder;
import com.sau.gym.admin.agent.tool.ToolResultCompactor;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:自建 ReAct 运行时
 * 说明:
 * 1. 只通过模型的 Function Calling 协议决定工具调用，不用提示词加正则模拟。
 * 2. 多个工具调用本轮按顺序串行执行，每个 tool_call_id 都会得到一条 tool 消息。
 * 3. 有明确终止条件: 正常回答、追问、待确认、失败、预算耗尽。
 */
@Component
public class DefaultReActRuntime implements ReActRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultReActRuntime.class);

    /**
     * 单轮内允许出现的重复调用终止次数。
     * 达到后不再继续请求模型，直接有界结束。
     */
    private static final int MAX_REPEAT_TERMINATIONS = 2;

    /**
     * 模型因输出截断时的用户提示。
     */
    private static final String TRUNCATED_REPLY = "模型输出被截断，本轮未执行任何可能不完整的工具调用。请把问题描述得更简短后重试。";

    private final ModelInvocationChain modelChain;

    private final ToolInvocationChain toolChain;

    private final GymAgentToolRegistry registry;

    private final AgentToolSchemaBuilder schemaBuilder;

    private final ToolResultCompactor resultCompactor;

    private final AgentContextBudgeter contextBudgeter;

    private final ObjectMapper objectMapper;

    @Autowired
    public DefaultReActRuntime(List<ModelInvocationInterceptor> modelInterceptors,
                               List<ToolInvocationInterceptor> toolInterceptors,
                               GymAgentToolRegistry registry,
                               AgentToolSchemaBuilder schemaBuilder,
                               ToolResultCompactor resultCompactor,
                               AgentContextBudgeter contextBudgeter,
                               ObjectMapper objectMapper,
                               LlmClient llmClient) {
        this.modelChain = ModelInvocationChain.of(modelInterceptors, llmClient);
        this.toolChain = new ToolInvocationChain(toolInterceptors, this::invokeTool);
        this.registry = registry;
        this.schemaBuilder = schemaBuilder;
        this.resultCompactor = resultCompactor;
        this.contextBudgeter = contextBudgeter;
        this.objectMapper = objectMapper;
    }

    /**
     * 测试用构造:允许直接传入已组装好的链。
     */
    public DefaultReActRuntime(ModelInvocationChain modelChain,
                               ToolInvocationChain toolChain,
                               GymAgentToolRegistry registry,
                               AgentToolSchemaBuilder schemaBuilder,
                               ToolResultCompactor resultCompactor,
                               AgentContextBudgeter contextBudgeter,
                               ObjectMapper objectMapper) {
        this.modelChain = modelChain;
        this.toolChain = toolChain;
        this.registry = registry;
        this.schemaBuilder = schemaBuilder;
        this.resultCompactor = resultCompactor;
        this.contextBudgeter = contextBudgeter;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentRunResult run(AgentRunContext runContext) {
        long start = System.currentTimeMillis();

        //本轮新增消息单独记录，避免上下文裁剪影响结果提交。
        List<SessionMessage> produced = new ArrayList<>();

        //历史中的用户消息只加入一次，当前输入在这里补入。
        SessionMessage userMessage = SessionMessage.user(runContext.getOriginalQuestion());
        runContext.addMessage(userMessage);
        produced.add(userMessage);

        List<LlmToolDefinition> tools = schemaBuilder.build(runContext.getAllowedToolCodes());
        int toolSchemaTokens = estimateToolSchemaTokens(tools);

        int attempt = 0;
        int repeatTerminations = 0;

        try {
            while (true) {
                if (!runContext.canContinue()) {
                    return budgetExceeded(runContext, produced, start, "TIMEOUT",
                            "本轮已停止：会话被其他请求接管或耗时预算耗尽，部分操作未完成。");
                }

                if (!runContext.hasModelCallBudget()) {
                    return budgetExceeded(runContext, produced, start, "MODEL_CALL_LIMIT",
                            degradeText("模型调用轮数已达上限"));
                }

                //每次模型调用前按完整轮次裁剪历史，保留当前输入与关键状态。
                contextBudgeter.enforce(runContext, toolSchemaTokens);

                attempt++;

                ModelCallRequest modelRequest = ModelCallRequest.builder()
                        .runContext(runContext)
                        .messages(toLlmMessages(runContext.getMessages()))
                        .tools(tools)
                        .attempt(attempt)
                        .build();

                LlmChatResponse response = modelChain.proceed(modelRequest);

                List<LlmToolCall> toolCalls = normalizeToolCalls(response.toolCalls());

                //保存模型实际返回的 assistant 消息，content 与 tool_calls 同时保留。
                SessionMessage assistantMessage = SessionMessage.assistant(response.content(), toolCalls);
                runContext.addMessage(assistantMessage);
                produced.add(assistantMessage);

                if (toolCalls.isEmpty()) {
                    String content = response.content();

                    if (!StringUtils.hasText(content)) {
                        return failed(runContext, produced, start,
                                "模型没有返回有效内容，请稍后重试。", "EMPTY_MODEL_CONTENT");
                    }

                    return complete(runContext, produced, start, content.trim());
                }

                if ("length".equalsIgnoreCase(response.finishReason())) {
                    //输出被截断时参数可能不完整，一律不执行，并为每个调用补齐结果。
                    appendPausedToolMessages(runContext, produced, toolCalls,
                            "模型输出被截断，本条工具调用未执行。");

                    return failed(runContext, produced, start, TRUNCATED_REPLY, "FINISH_REASON_LENGTH");
                }

                AgentToolExecuteResult confirmResult = null;
                boolean paused = false;

                int index = 0;

                for (LlmToolCall toolCall : toolCalls) {
                    index++;

                    if (paused) {
                        //已进入待确认终态，剩余工具补齐“暂停/未执行”结果，不再继续操作。
                        appendToolMessage(runContext, produced, toolCall,
                                pausedToolResult("本轮已进入待确认状态，该工具未执行。"));
                        continue;
                    }

                    ToolCallRequest toolRequest = buildToolRequest(runContext, toolCall, index);
                    AgentToolExecuteResult result = toolChain.proceed(toolRequest);

                    appendToolMessage(runContext, produced, toolCall, result);

                    if (result != null && result.isNeedConfirm()) {
                        paused = true;
                        confirmResult = result;
                        continue;
                    }

                    if (result != null && "REPEAT_LIMIT".equals(result.getErrorCode())) {
                        repeatTerminations++;
                    }
                }

                if (confirmResult != null) {
                    return waitingConfirmation(runContext, produced, start, confirmResult);
                }

                if (repeatTerminations >= MAX_REPEAT_TERMINATIONS) {
                    return failed(runContext, produced, start,
                            "检测到工具被重复调用且没有产生新结果，本轮已停止。请补充更明确的条件后重试。",
                            "REPEAT_LIMIT");
                }
            }

        } catch (AgentBudgetExceededException e) {
            return budgetExceeded(runContext, produced, start, e.getReasonCode(), degradeText(e.getMessage()));

        } catch (LlmException e) {
            log.warn("[AgentRuntime] 模型调用失败, type={}, reason={}", e.getErrorType(), e.getMessage());

            String reply = switch (e.getErrorType()) {
                case TIMEOUT -> "AI 模型响应超时，请稍后重试，或把问题描述得更简短一些。";
                case RATE_LIMITED -> "AI 模型当前请求较多，请稍后再试。";
                case AUTH_ERROR, CONFIG_ERROR -> "AI 模型配置异常，请联系管理员检查配置。";
                default -> "AI 服务暂时不可用，请稍后再试。";
            };

            return failed(runContext, produced, start, reply, "LLM_" + e.getErrorType());
        }
    }

    /**
     * 链尾执行器:构造显式上下文并调用 Registry。
     */
    private AgentToolExecuteResult invokeTool(ToolCallRequest request) {
        AgentRunContext runContext = request.getRunContext();

        AgentToolExecuteContext executeContext = new AgentToolExecuteContext();
        executeContext.setUserId(runContext.getUserId());
        executeContext.setSessionId(runContext.getSessionId());
        executeContext.setTraceId(runContext.getRunId());
        executeContext.setRunId(runContext.getRunId());
        executeContext.setOriginalQuestion(runContext.getOriginalQuestion());
        executeContext.setBusinessContext(runContext.getBusinessContext());
        executeContext.setRemainingBudgetMs(runContext.remainingTimeMs());
        executeContext.setParams(request.getArguments() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getArguments()));

        return registry.execute(request.getToolCode(), executeContext);
    }

    /**
     * 构造工具派发请求，参数 JSON 解析失败时携带明确错误。
     */
    private ToolCallRequest buildToolRequest(AgentRunContext runContext, LlmToolCall toolCall, int index) {
        String argumentsJson = toolCall.argumentsJson();
        Map<String, Object> arguments = new LinkedHashMap<>();
        String argumentError = null;

        if (StringUtils.hasText(argumentsJson)) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(
                        argumentsJson,
                        new TypeReference<Map<String, Object>>() {
                        }
                );

                if (parsed != null) {
                    arguments.putAll(parsed);
                }
            } catch (Exception e) {
                argumentError = "arguments parse failed: " + e.getMessage();
            }
        }

        return ToolCallRequest.builder()
                .runContext(runContext)
                .toolCallId(toolCall.getId())
                .toolCode(toolCall.functionName())
                .argumentsJson(argumentsJson)
                .arguments(arguments)
                .callIndex(index)
                .argumentError(argumentError)
                .build();
    }

    /**
     * 补齐工具调用 ID。
     * 模型漏传 id 时生成稳定 ID，保证 assistant 与 tool 消息可以配对。
     */
    private List<LlmToolCall> normalizeToolCalls(List<LlmToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }

        List<LlmToolCall> normalized = new ArrayList<>(toolCalls.size());

        int index = 0;

        for (LlmToolCall call : toolCalls) {
            index++;

            if (call == null) {
                continue;
            }

            if (!StringUtils.hasText(call.getId())) {
                call.setId("call_auto_" + index);
            }

            if (!StringUtils.hasText(call.getType())) {
                call.setType("function");
            }

            normalized.add(call);
        }

        return normalized;
    }

    /**
     * 追加 tool 消息。
     */
    private void appendToolMessage(AgentRunContext runContext,
                                   List<SessionMessage> produced,
                                   LlmToolCall toolCall,
                                   AgentToolExecuteResult result) {
        String content = resultCompactor.compact(result, runContext.getMaxToolResultChars());

        SessionMessage message = SessionMessage.tool(
                toolCall.getId(),
                toolCall.functionName(),
                content
        );

        runContext.addMessage(message);
        produced.add(message);
    }

    /**
     * 为未执行的工具补齐暂停结果。
     */
    private void appendPausedToolMessages(AgentRunContext runContext,
                                          List<SessionMessage> produced,
                                          List<LlmToolCall> toolCalls,
                                          String reason) {
        for (LlmToolCall toolCall : toolCalls) {
            appendToolMessage(runContext, produced, toolCall, pausedToolResult(reason));
        }
    }

    /**
     * 构造“未执行”的结构化工具结果。
     */
    private AgentToolExecuteResult pausedToolResult(String reason) {
        AgentToolExecuteResult result = AgentToolExecuteResult.failed(reason, "NOT_EXECUTED");
        result.setErrorCode("NOT_EXECUTED");
        return result;
    }

    /**
     * 转换为模型请求消息。
     */
    private List<LlmMessage> toLlmMessages(List<SessionMessage> messages) {
        List<LlmMessage> result = new ArrayList<>(messages.size());

        for (SessionMessage message : messages) {
            if (message != null) {
                result.add(message.toLlmMessage());
            }
        }

        return result;
    }

    /**
     * 估算工具 Schema 的 Token 开销。
     */
    private int estimateToolSchemaTokens(List<LlmToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }

        int total = 0;

        for (LlmToolDefinition tool : tools) {
            if (tool == null || tool.getFunction() == null) {
                continue;
            }

            total += TokenEstimator.estimate(tool.getFunction().getName());
            total += TokenEstimator.estimate(tool.getFunction().getDescription());

            try {
                total += TokenEstimator.estimate(objectMapper.writeValueAsString(tool.getFunction().getParameters()));
            } catch (Exception e) {
                total += 120;
            }
        }

        return total;
    }

    /**
     * 正常完成。
     */
    private AgentRunResult complete(AgentRunContext runContext,
                                    List<SessionMessage> produced,
                                    long start,
                                    String replyText) {
        AgentRunResult result = AgentRunResult.completed(replyText);
        fill(result, runContext, produced, start);
        return result;
    }

    /**
     * 失败终态。
     * 补一条 assistant 消息，保证会话历史不会留下没有回复的用户消息。
     */
    private AgentRunResult failed(AgentRunContext runContext,
                                  List<SessionMessage> produced,
                                  long start,
                                  String replyText,
                                  String errorCode) {
        AgentRunResult result = AgentRunResult.failed(replyText, errorCode);
        produced.add(SessionMessage.assistant(replyText));
        fill(result, runContext, produced, start);
        return result;
    }

    /**
     * 预算耗尽终态。
     */
    private AgentRunResult budgetExceeded(AgentRunContext runContext,
                                          List<SessionMessage> produced,
                                          long start,
                                          String errorCode,
                                          String replyText) {
        AgentRunResult result = AgentRunResult.budgetExceeded(replyText, errorCode);
        produced.add(SessionMessage.assistant(replyText));
        fill(result, runContext, produced, start);
        return result;
    }

    /**
     * 待确认终态。
     * 展示内容必须来自后端工具结果，不能由模型重新生成。
     */
    private AgentRunResult waitingConfirmation(AgentRunContext runContext,
                                               List<SessionMessage> produced,
                                               long start,
                                               AgentToolExecuteResult confirmResult) {
        String payload;

        try {
            payload = objectMapper.writeValueAsString(confirmResult);
        } catch (Exception e) {
            payload = null;
        }

        AgentRunResult result = AgentRunResult.waitingConfirmation(
                confirmResult.getMessage(),
                payload,
                confirmResult.getConfirmAction()
        );

        fill(result, runContext, produced, start);
        return result;
    }

    /**
     * 填充公共字段。
     */
    private void fill(AgentRunResult result,
                      AgentRunContext runContext,
                      List<SessionMessage> produced,
                      long start) {
        result.setNewMessages(new ArrayList<>(produced));
        result.setModelCallCount(runContext.getModelCallCount());
        result.setToolCallCount(runContext.getToolCallCount());
        result.setCostMs(System.currentTimeMillis() - start);
    }

    /**
     * 预算终止时的降级说明，不假称业务成功。
     */
    private String degradeText(String detail) {
        String suffix = StringUtils.hasText(detail) ? "（" + detail + "）" : "";
        return "本轮处理已达到系统限制" + suffix
                + "，部分查询或操作没有完成。已返回的内容仅供参考，请缩小问题范围或分多次提问。";
    }
}

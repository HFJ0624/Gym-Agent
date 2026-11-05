package com.sau.gym.admin.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.config.AgentHarnessProperties;
import com.sau.gym.admin.agent.context.AgentSystemPromptBuilder;
import com.sau.gym.admin.agent.intent.GymIntentRouter;
import com.sau.gym.admin.agent.intent.IntentRouteRequest;
import com.sau.gym.admin.agent.intent.IntentRouteResult;
import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.memory.service.AgentSessionMessageStore;
import com.sau.gym.admin.agent.rewrite.QuestionRewriteRequest;
import com.sau.gym.admin.agent.rewrite.QuestionRewriteResult;
import com.sau.gym.admin.agent.rewrite.service.QuestionRewriteService;
import com.sau.gym.admin.agent.runtime.AgentRunContext;
import com.sau.gym.admin.agent.runtime.AgentRunResult;
import com.sau.gym.admin.agent.runtime.ReActRuntime;
import com.sau.gym.admin.agent.security.AgentSafetyCheckResult;
import com.sau.gym.admin.agent.service.AgentContextEnhanceService;
import com.sau.gym.admin.agent.service.AgentDirectRouteService;
import com.sau.gym.admin.agent.service.AgentSafetyService;
import com.sau.gym.admin.agent.service.AgentTraceService;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.AgentSessionLockService;
import com.sau.gym.admin.agent.tool.AgentToolSelector;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.util.AgentSessionIdUtil;
import com.sau.gym.admin.agent.workflow.AgentConfirmationWorkflow;
import com.sau.gym.admin.agent.workflow.AgentWorkflowRouter;
import com.sau.gym.admin.agent.workflow.WorkflowDecision;
import com.sau.gym.admin.agent.workflow.WorkflowType;
import com.sau.gym.model.dto.agent.AgentChatDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 作者:hfj
 * 功能:Agent Harness 默认实现
 * 职责:
 * 1. 会话串行控制与历史加载。
 * 2. 输入安全检查。
 * 3. 业务上下文准备、意图识别、问题重写。
 * 4. 选择确认/取消/追问/直达/ReAct 流程。
 * 5. 统一提交会话消息并结束本轮。
 */
@Component
public class DefaultGymAgentHarness implements GymAgentHarness {

    private static final Logger log = LoggerFactory.getLogger(DefaultGymAgentHarness.class);

    private final AgentHarnessProperties properties;

    private final AgentSessionMessageStore messageStore;

    private final AgentSessionLockService lockService;

    private final AgentSafetyService safetyService;

    private final AgentContextEnhanceService contextEnhanceService;

    private final GymIntentRouter intentRouter;

    private final QuestionRewriteService questionRewriteService;

    private final AgentWorkflowRouter workflowRouter;

    private final AgentDirectRouteService directRouteService;

    private final AgentConfirmationWorkflow confirmationWorkflow;

    private final AgentToolSelector toolSelector;

    private final AgentSystemPromptBuilder promptBuilder;

    private final ReActRuntime reActRuntime;

    private final AgentTraceService traceService;

    private final AgentDraftStore draftStore;

    private final ObjectMapper objectMapper;

    public DefaultGymAgentHarness(AgentHarnessProperties properties,
                                  AgentSessionMessageStore messageStore,
                                  AgentSessionLockService lockService,
                                  AgentSafetyService safetyService,
                                  AgentContextEnhanceService contextEnhanceService,
                                  GymIntentRouter intentRouter,
                                  QuestionRewriteService questionRewriteService,
                                  AgentWorkflowRouter workflowRouter,
                                  AgentDirectRouteService directRouteService,
                                  AgentConfirmationWorkflow confirmationWorkflow,
                                  AgentToolSelector toolSelector,
                                  AgentSystemPromptBuilder promptBuilder,
                                  ReActRuntime reActRuntime,
                                  AgentTraceService traceService,
                                  AgentDraftStore draftStore,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.messageStore = messageStore;
        this.lockService = lockService;
        this.safetyService = safetyService;
        this.contextEnhanceService = contextEnhanceService;
        this.intentRouter = intentRouter;
        this.questionRewriteService = questionRewriteService;
        this.workflowRouter = workflowRouter;
        this.directRouteService = directRouteService;
        this.confirmationWorkflow = confirmationWorkflow;
        this.toolSelector = toolSelector;
        this.promptBuilder = promptBuilder;
        this.reActRuntime = reActRuntime;
        this.traceService = traceService;
        this.draftStore = draftStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentRunResult run(AgentHarnessRequest request) {
        long start = System.currentTimeMillis();

        String sessionId = AgentSessionIdUtil.normalize(request.getSessionId());
        Long userId = request.getUserId();
        String runId = request.getRunId();
        String message = request.getMessage() == null ? "" : request.getMessage().trim();

        //同一会话串行处理，后来者明确被拒绝，避免消息与草稿互相覆盖。
        AgentSessionLockService.LockHandle lock = lockService.tryLock(
                userId,
                sessionId,
                Duration.ofMillis(resolveLockTtl())
        );

        if (lock == null) {
            return AgentRunResult.failed("当前会话正在处理上一条消息，请稍后再试。", "SESSION_BUSY");
        }

        try {
            trace("HARNESS_START", "开始处理本轮请求",
                    "sessionId=" + sessionId + ", runId=" + runId, null, "SUCCESS", null, 0L);

            //1. 输入安全检查。
            AgentSafetyCheckResult safetyResult = safetyService.checkUserMessage(userId, message);

            if (!safetyResult.isAllowed()) {
                AgentRunResult result = AgentRunResult.completed(safetyResult.getReason());
                trace("SAFETY_CHECK", "用户输入安全检查未通过", message,
                        result.getReplyText(), "FAILED", safetyResult.getReason(), 0L);
                return finish(result, request, sessionId);
            }

            //2. 业务上下文准备。
            AgentBusinessContext businessContext = contextEnhanceService.prepareBeforeAgent(
                    userId, sessionId, message, request.getChatDto()
            );

            //3. 意图识别与问题重写，属于辅助信息，失败不应阻断主流程。
            IntentRouteResult intentResult = routeIntent(userId, message, businessContext, request.getChatDto());
            QuestionRewriteResult rewriteResult = rewrite(userId, message, intentResult, businessContext,
                    request.getChatDto());

            //4. 流程选择。
            WorkflowDecision decision = workflowRouter.decide(message, intentResult);

            trace("WORKFLOW_SELECT", "选择流程: " + decision.getType(),
                    "sessionId=" + sessionId + ", runId=" + runId + ", reason=" + decision.getReason(),
                    null, "SUCCESS", null, 0L);

            AgentRunResult result = dispatch(decision, request, sessionId, businessContext, intentResult, rewriteResult);

            //5. 草稿生成后把准确槽位回写业务上下文。
            refreshContext(userId, sessionId);

            return finish(result, request, sessionId);

        } catch (Exception e) {
            //不向用户暴露堆栈，只记录可观察信息。
            log.error("[AgentHarness] 处理失败, sessionId={}", sessionId, e);

            trace("HARNESS_FAILED", "本轮处理异常", message, null, "FAILED", e.getMessage(),
                    System.currentTimeMillis() - start);

            AgentRunResult result = AgentRunResult.failed("AI 服务暂时不可用，请稍后再试。", "HARNESS_ERROR");
            result.setNewMessages(buildSimpleMessages(message, result.getReplyText()));

            commitQuietly(userId, sessionId, result);
            return result;

        } finally {
            lock.release();
        }
    }

    /**
     * 按流程类型分发。
     */
    private AgentRunResult dispatch(WorkflowDecision decision,
                                    AgentHarnessRequest request,
                                    String sessionId,
                                    AgentBusinessContext businessContext,
                                    IntentRouteResult intentResult,
                                    QuestionRewriteResult rewriteResult) {

        Long userId = request.getUserId();
        String message = request.getMessage() == null ? "" : request.getMessage().trim();

        return switch (decision.getType()) {
            case CONFIRMATION -> confirmationWorkflow.handle(decision, userId, sessionId, message);

            case CANCEL_BOOKING_DRAFT -> confirmationWorkflow.handleCancelBookingDraft(
                    String.valueOf(decision.getPayload().get("bookingId")),
                    userId,
                    sessionId,
                    message
            );

            case CLARIFY -> {
                Object question = decision.getPayload().get("question");
                yield AgentRunResult.clarification(
                        question == null ? "请补充更多信息后再试。" : String.valueOf(question)
                );
            }

            case DIRECT_ROUTE -> runDirectRoute(request, sessionId, businessContext, message, intentResult, rewriteResult);

            case REACT -> runReact(request, sessionId, businessContext, intentResult, rewriteResult);
        };
    }

    /**
     * 直达路由:命中则直接返回工具结果，未命中回落到 ReAct。
     */
    private AgentRunResult runDirectRoute(AgentHarnessRequest request,
                                          String sessionId,
                                          AgentBusinessContext businessContext,
                                          String message,
                                          IntentRouteResult intentResult,
                                          QuestionRewriteResult rewriteResult) {

        Long userId = request.getUserId();
        AgentChatDto dto = request.getChatDto();

        Long venueId = contextEnhanceService.getEffectiveVenueId(dto, businessContext);
        Long courtId = contextEnhanceService.getEffectiveCourtId(dto, businessContext);

        AgentToolExecuteResult toolResult = directRouteService.tryHandle(
                userId, sessionId, message, venueId, courtId, businessContext
        );

        if (toolResult == null) {
            return runReact(request, sessionId, businessContext, intentResult, rewriteResult);
        }

        trace("DIRECT_ROUTE", "命中直达路由",
                "venueId=" + venueId + ", courtId=" + courtId,
                toolResult.getMessage(),
                toolResult.isSuccess() ? "SUCCESS" : "FAILED",
                toolResult.getErrorDetail(),
                toolResult.getCostTimeMs());

        return toRunResult(toolResult);
    }

    /**
     * ReAct 路径。
     */
    private AgentRunResult runReact(AgentHarnessRequest request,
                                    String sessionId,
                                    AgentBusinessContext businessContext,
                                    IntentRouteResult intentResult,
                                    QuestionRewriteResult rewriteResult) {

        Long userId = request.getUserId();
        String message = request.getMessage() == null ? "" : request.getMessage().trim();

        LinkedHashSet<String> allowedTools = toolSelector.select(
                intentResult == null ? null : intentResult.getIntent(),
                message
        );

        trace("TOOLS_ALLOWED", "本轮授权工具",
                "sessionId=" + sessionId, String.join(",", allowedTools), "SUCCESS", null, 0L);

        AgentRunContext runContext = AgentRunContext.create(
                properties,
                userId,
                sessionId,
                request.getRunId(),
                message,
                businessContext,
                allowedTools
        );

        //系统提示只组装一次，历史消息随后拼接，当前输入由运行时补入。
        String systemPrompt = promptBuilder.build(
                contextEnhanceService.buildContextPrompt(businessContext),
                buildPagePrompt(request.getChatDto(), businessContext),
                intentResult == null ? null : intentResult.getRoutePrompt(),
                rewriteResult == null ? null : rewriteResult.getRewritePrompt(),
                allowedTools,
                hasPendingDraft(userId, sessionId)
        );

        List<SessionMessage> messages = new ArrayList<>();
        messages.add(SessionMessage.system(systemPrompt));
        messages.addAll(messageStore.load(userId, sessionId));
        runContext.setMessages(messages);

        return reActRuntime.run(runContext);
    }

    /**
     * 把工具结果转换成运行结果。
     * 草稿类结果必须保留后端组装的结构化数据，供前端渲染卡片。
     */
    private AgentRunResult toRunResult(AgentToolExecuteResult toolResult) {
        if (toolResult.isNeedConfirm()) {
            String payload = null;

            try {
                payload = objectMapper.writeValueAsString(toolResult);
            } catch (Exception e) {
                log.warn("[AgentHarness] 草稿结果序列化失败: {}", e.getMessage());
            }

            return AgentRunResult.waitingConfirmation(
                    toolResult.getMessage(),
                    payload,
                    toolResult.getConfirmAction()
            );
        }

        return AgentRunResult.completed(String.valueOf(toolResult.getMessage()));
    }

    /**
     * 结束本轮:提交会话消息、写最终 Trace。
     */
    private AgentRunResult finish(AgentRunResult result, AgentHarnessRequest request, String sessionId) {
        if (result == null) {
            result = AgentRunResult.failed("本次没有生成有效回复，请稍后再试。", "EMPTY_RESULT");
        }

        if (!StringUtils.hasText(result.getReplyText()) && StringUtils.hasText(result.getDisplayPayload())) {
            result.setReplyText("已生成待确认草稿，请确认或取消。");
        }

        if (result.getNewMessages() == null || result.getNewMessages().isEmpty()) {
            result.setNewMessages(buildSimpleMessages(request.getMessage(), result.getReplyText()));
        }

        boolean committed = messageStore.append(request.getUserId(), sessionId, result.getNewMessages());
        result.setSessionCommitted(committed);

        if (!committed) {
            trace("SESSION_COMMIT", "会话消息提交失败", sessionId, null, "FAILED",
                    "session message store append returned false", 0L);

            //文本回复时明确提示，避免用户以为多轮上下文已经保存。
            if (!StringUtils.hasText(result.getDisplayPayload())) {
                result.setReplyText(result.getReplyText()
                        + "\n\n（提示：本轮会话记录保存失败，多轮上下文可能不完整。）");
            }
        }

        trace("FINAL_REPLY", "本轮结束: " + result.getStatus(),
                "sessionId=" + sessionId,
                result.getReplyText(),
                result.isBusinessSuccess() ? "SUCCESS" : "FAILED",
                result.getErrorCode(),
                result.getCostMs());

        return result;
    }

    /**
     * 无工具调用分支的消息组装。
     */
    private List<SessionMessage> buildSimpleMessages(String userMessage, String replyText) {
        List<SessionMessage> messages = new ArrayList<>();
        messages.add(SessionMessage.user(userMessage));
        messages.add(SessionMessage.assistant(replyText));
        return messages;
    }

    /**
     * 安静提交，异常分支使用。
     */
    private void commitQuietly(Long userId, String sessionId, AgentRunResult result) {
        try {
            messageStore.append(userId, sessionId, result.getNewMessages());
        } catch (Exception e) {
            log.warn("[AgentHarness] 异常分支会话提交失败: {}", e.getMessage());
        }
    }

    /**
     * 意图识别，异常时回落到 ReAct。
     */
    private IntentRouteResult routeIntent(Long userId,
                                          String message,
                                          AgentBusinessContext businessContext,
                                          AgentChatDto dto) {
        long start = System.currentTimeMillis();

        try {
            IntentRouteRequest intentRequest = new IntentRouteRequest();
            intentRequest.setUserId(userId);
            intentRequest.setMessage(message);
            intentRequest.setEffectiveVenueId(contextEnhanceService.getEffectiveVenueId(dto, businessContext));
            intentRequest.setEffectiveCourtId(contextEnhanceService.getEffectiveCourtId(dto, businessContext));
            intentRequest.setBusinessContext(businessContext);

            IntentRouteResult result = intentRouter.route(intentRequest);

            trace("INTENT_ROUTE", "意图识别完成", message,
                    result == null ? null : result.getIntent() + "/" + result.getConfidence(),
                    "SUCCESS", null, System.currentTimeMillis() - start);

            return result;
        } catch (Exception e) {
            trace("INTENT_ROUTE", "意图识别失败", message, null, "FAILED", e.getMessage(),
                    System.currentTimeMillis() - start);
            return null;
        }
    }

    /**
     * 问题重写，异常时使用原问题。
     */
    private QuestionRewriteResult rewrite(Long userId,
                                          String message,
                                          IntentRouteResult intentResult,
                                          AgentBusinessContext businessContext,
                                          AgentChatDto dto) {
        long start = System.currentTimeMillis();

        try {
            QuestionRewriteRequest rewriteRequest = new QuestionRewriteRequest();
            rewriteRequest.setUserId(userId);
            rewriteRequest.setOriginalQuestion(message);
            rewriteRequest.setIntentRouteResult(intentResult);
            rewriteRequest.setEffectiveVenueId(contextEnhanceService.getEffectiveVenueId(dto, businessContext));
            rewriteRequest.setEffectiveCourtId(contextEnhanceService.getEffectiveCourtId(dto, businessContext));
            rewriteRequest.setBusinessContext(businessContext);
            rewriteRequest.setAgentChatDto(dto);

            QuestionRewriteResult result = questionRewriteService.rewrite(rewriteRequest);

            trace("QUESTION_REWRITE", "问题重写完成", message,
                    result == null ? null : result.getRewrittenQuestion(),
                    "SUCCESS", null, System.currentTimeMillis() - start);

            return result == null ? QuestionRewriteResult.noChange(message) : result;
        } catch (Exception e) {
            trace("QUESTION_REWRITE", "问题重写失败", message, null, "FAILED", e.getMessage(),
                    System.currentTimeMillis() - start);
            return QuestionRewriteResult.noChange(message);
        }
    }

    /**
     * 刷新业务上下文，失败不影响主流程。
     */
    private void refreshContext(Long userId, String sessionId) {
        try {
            contextEnhanceService.refreshAfterAgent(userId, sessionId);
        } catch (Exception e) {
            trace("CONTEXT_REFRESH", "刷新业务上下文失败", null, null, "FAILED", e.getMessage(), 0L);
        }
    }

    /**
     * 当前页面上下文提示。
     */
    private String buildPagePrompt(AgentChatDto dto, AgentBusinessContext businessContext) {
        StringBuilder builder = new StringBuilder();

        if (dto != null && dto.getVenueId() != null) {
            builder.append("当前页面场馆ID：").append(dto.getVenueId()).append("\n");
        }

        if (dto != null && dto.getCourtId() != null) {
            builder.append("当前页面场地ID：").append(dto.getCourtId()).append("\n");
        }

        if ((dto == null || dto.getVenueId() == null)
                && businessContext != null && businessContext.getLastVenueId() != null) {
            builder.append("本次请求未传当前场馆ID，参考最近场馆ID：")
                    .append(businessContext.getLastVenueId()).append("\n");
        }

        if ((dto == null || dto.getCourtId() == null)
                && businessContext != null && businessContext.getLastCourtId() != null) {
            builder.append("本次请求未传当前场地ID，参考最近场地ID：")
                    .append(businessContext.getLastCourtId()).append("\n");
        }

        return builder.toString();
    }

    /**
     * 是否存在待确认草稿。
     */
    private boolean hasPendingDraft(Long userId, String sessionId) {
        try {
            return userId != null && draftStore.hasDraft(userId, sessionId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 安全写 Trace，日志异常不能影响用户咨询。
     */
    private void trace(String stepType,
                       String stepName,
                       String input,
                       String output,
                       String status,
                       String errorMessage,
                       Long costMs) {
        try {
            traceService.addStep(stepType, stepName, input, output, status, errorMessage, costMs);
        } catch (Exception e) {
            log.warn("[AgentHarness] Trace 写入失败, step={}, reason={}", stepType, e.getMessage());
        }
    }

    /**
     * 会话锁有效期兜底。
     */
    private long resolveLockTtl() {
        long ttl = properties.getSessionLockTtlMs();
        return ttl > 0 ? ttl : 60000L;
    }
}

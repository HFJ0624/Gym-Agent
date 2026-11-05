package com.sau.gym.admin.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.config.AgentHarnessProperties;
import com.sau.gym.admin.agent.context.AgentContextBudgeter;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ModelBudgetInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ModelTraceInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ToolArgumentInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ToolAuthorizationInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ToolBudgetInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ToolTraceInterceptor;
import com.sau.gym.admin.agent.llm.LlmChatResponse;
import com.sau.gym.admin.agent.llm.LlmChoice;
import com.sau.gym.admin.agent.llm.LlmClient;
import com.sau.gym.admin.agent.llm.LlmFunctionCall;
import com.sau.gym.admin.agent.llm.LlmResponseMessage;
import com.sau.gym.admin.agent.llm.LlmToolCall;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.service.AgentToolGuardService;
import com.sau.gym.admin.agent.service.AgentTraceService;
import com.sau.gym.admin.agent.tool.AgentToolSchemaBuilder;
import com.sau.gym.admin.agent.tool.ToolResultCompactor;
import com.sau.gym.admin.agent.tool.executor.AbstractGymAgentToolExecutor;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.executor.AgentToolParamDefinition;
import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import com.sau.gym.admin.enums.AgentRiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 作者:hfj
 * 功能:自建 ReAct 运行时单元测试
 * 说明:
 * 使用 Fake LlmClient 与测试工具执行器，不依赖真实模型 Key、数据库或外网。
 */
class DefaultReActRuntimeTest {

    private static final String TOOL_QUERY = "test_query";

    private static final String TOOL_DRAFT = "test_draft";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final AgentHarnessProperties properties = new AgentHarnessProperties();

    private FakeLlmClient llmClient;

    private TestToolExecutor queryExecutor;

    private TestToolExecutor draftExecutor;

    private GymAgentToolRegistry registry;

    private DefaultReActRuntime runtime;

    @BeforeEach
    void setUp() {
        properties.setMaxAgentModelCalls(6);
        properties.setMaxToolCalls(8);
        properties.setMaxRunDurationMs(30000);
        properties.setMaxContextTokens(12000);
        properties.setMaxToolResultChars(6000);
        properties.setRepeatToolThreshold(3);

        queryExecutor = new TestToolExecutor(
                TOOL_QUERY,
                List.of(new AgentToolParamDefinition("keyword", "查询关键词", "String", true, "羽毛球")),
                context -> AgentToolExecuteResult.success("查询完成", Map.of("items", List.of("场地A")))
        );

        draftExecutor = new TestToolExecutor(
                TOOL_DRAFT,
                List.of(new AgentToolParamDefinition("venueId", "场馆ID", "Long", true, "1")),
                context -> AgentToolExecuteResult.needConfirm(
                        "已生成草稿",
                        Map.of("venueId", 1),
                        "confirm_booking"
                ).addExtra("confirmToken", "123456")
        );

        registry = new GymAgentToolRegistry(List.of(queryExecutor, draftExecutor));
        registry.init();

        AgentTraceService traceService = mock(AgentTraceService.class);

        List<ModelInvocationInterceptor> modelInterceptors = List.of(
                new ModelBudgetInterceptor(),
                new ModelTraceInterceptor(traceService)
        );

        List<ToolInvocationInterceptor> toolInterceptors = List.of(
                new ToolBudgetInterceptor(objectMapper),
                new ToolAuthorizationInterceptor(registry),
                new ToolArgumentInterceptor(registry),
                new ToolTraceInterceptor(traceService, objectMapper)
        );

        llmClient = new FakeLlmClient();

        runtime = new DefaultReActRuntime(
                modelInterceptors,
                toolInterceptors,
                registry,
                new AgentToolSchemaBuilder(registry),
                new ToolResultCompactor(objectMapper),
                new AgentContextBudgeter(),
                objectMapper,
                llmClient
        );
    }

    @Test
    void 普通回答应正常结束且用户消息只出现一次() {
        llmClient.enqueue(textResponse("你好，我可以帮你查询场馆和预约。"));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY)));

        assertEquals(AgentRunStatus.COMPLETED, result.getStatus());
        assertEquals("你好，我可以帮你查询场馆和预约。", result.getReplyText());

        long userCount = result.getNewMessages().stream()
                .filter(message -> "user".equals(message.getRole()))
                .count();

        assertEquals(1L, userCount, "历史中的用户消息只应加入一次");
    }

    @Test
    void 一次工具调用后应追加assistant与tool消息() {
        llmClient.enqueue(toolCallResponse("call_1", TOOL_QUERY, "{\"keyword\":\"羽毛球\"}"));
        llmClient.enqueue(textResponse("查到 1 个场地。"));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY)));

        assertEquals(AgentRunStatus.COMPLETED, result.getStatus());
        assertEquals(1, queryExecutor.getInvocations());

        List<SessionMessage> messages = result.getNewMessages();

        assertTrue(messages.stream().anyMatch(m -> "assistant".equals(m.getRole()) && m.getToolCalls() != null));
        SessionMessage toolMessage = messages.stream()
                .filter(m -> "tool".equals(m.getRole()))
                .findFirst()
                .orElseThrow();

        assertEquals("call_1", toolMessage.getToolCallId());
        assertTrue(toolMessage.getContent().contains("\"status\":\"SUCCESS\""));
    }

    @Test
    void 同一响应中的多个工具调用应按顺序各自返回结果() {
        List<LlmToolCall> calls = new ArrayList<>();
        calls.add(toolCall("call_a", TOOL_QUERY, "{\"keyword\":\"篮球\"}"));
        calls.add(toolCall("call_b", TOOL_DRAFT, "{\"venueId\":1}"));

        llmClient.enqueue(toolCallsResponse(calls));
        llmClient.enqueue(textResponse("已处理。"));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY, TOOL_DRAFT)));

        List<SessionMessage> toolMessages = result.getNewMessages().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .toList();

        assertEquals(2, toolMessages.size());
        assertEquals("call_a", toolMessages.get(0).getToolCallId());
        assertEquals("call_b", toolMessages.get(1).getToolCallId());
    }

    @Test
    void 未注册的工具不应执行() {
        llmClient.enqueue(toolCallResponse("call_x", "not_exist_tool", "{}"));
        llmClient.enqueue(textResponse("暂时无法查询该能力。"));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY)));

        assertEquals(0, queryExecutor.getInvocations());

        SessionMessage toolMessage = firstToolMessage(result);
        assertTrue(toolMessage.getContent().contains("没有可用工具"));
    }

    @Test
    void 本轮未授权的工具不应执行() {
        llmClient.enqueue(toolCallResponse("call_1", TOOL_QUERY, "{\"keyword\":\"羽毛球\"}"));
        llmClient.enqueue(textResponse("换个问题试试。"));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_DRAFT)));

        assertEquals(0, queryExecutor.getInvocations());

        SessionMessage toolMessage = firstToolMessage(result);
        assertTrue(toolMessage.getContent().contains("PERMISSION_DENIED"));
    }

    @Test
    void 参数缺失或类型错误时不应进入业务执行() {
        llmClient.enqueue(toolCallResponse("call_1", TOOL_QUERY, "{}"));
        llmClient.enqueue(toolCallResponse("call_2", TOOL_QUERY, "{\"keyword\":{\"nested\":1}}"));
        llmClient.enqueue(textResponse("请补充关键词。"));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY)));

        assertEquals(0, queryExecutor.getInvocations());

        List<SessionMessage> toolMessages = result.getNewMessages().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .toList();

        assertEquals(2, toolMessages.size());
        assertTrue(toolMessages.get(0).getContent().contains("PARAM_ERROR"));
        assertTrue(toolMessages.get(1).getContent().contains("PARAM_ERROR"));
    }

    @Test
    void 工具业务失败不应被当成成功() {
        queryExecutor.setHandler(context -> AgentToolExecuteResult.failed("场地暂不可用", "court disabled"));

        llmClient.enqueue(toolCallResponse("call_1", TOOL_QUERY, "{\"keyword\":\"羽毛球\"}"));
        llmClient.enqueue(textResponse("该场地暂不可用。"));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY)));

        SessionMessage toolMessage = firstToolMessage(result);
        assertTrue(toolMessage.getContent().contains("\"success\":false"));
        assertTrue(toolMessage.getContent().contains("\"status\":\"FAILED\""));
    }

    @Test
    void 需要确认的工具应结束本轮并保留后端草稿结构() {
        List<LlmToolCall> calls = new ArrayList<>();
        calls.add(toolCall("call_draft", TOOL_DRAFT, "{\"venueId\":1}"));
        calls.add(toolCall("call_after", TOOL_QUERY, "{\"keyword\":\"羽毛球\"}"));

        llmClient.enqueue(toolCallsResponse(calls));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY, TOOL_DRAFT)));

        assertEquals(AgentRunStatus.WAITING_CONFIRMATION, result.getStatus());
        assertNotNull(result.getDisplayPayload());
        assertTrue(result.getDisplayPayload().contains("\"confirmAction\":\"confirm_booking\""));
        assertTrue(result.getDisplayPayload().contains("\"confirmToken\":\"123456\""));

        //待确认后不再执行剩余工具，但必须补齐未执行结果。
        assertEquals(0, queryExecutor.getInvocations());

        List<SessionMessage> toolMessages = result.getNewMessages().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .toList();

        assertEquals(2, toolMessages.size());
        assertTrue(toolMessages.get(1).getContent().contains("NOT_EXECUTED"));
    }

    @Test
    void 超过模型轮数上限应返回预算终止而不是假称成功() {
        properties.setMaxAgentModelCalls(2);

        llmClient.enqueue(toolCallResponse("call_1", TOOL_QUERY, "{\"keyword\":\"篮球\"}"));
        llmClient.enqueue(toolCallResponse("call_2", TOOL_QUERY, "{\"keyword\":\"篮球\"}"));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY)));

        assertEquals(AgentRunStatus.BUDGET_EXCEEDED, result.getStatus());
        assertEquals("MODEL_CALL_LIMIT", result.getErrorCode());
        assertTrue(result.getReplyText().contains("系统限制"));
        assertFalse(result.getReplyText().contains("成功"));
    }

    @Test
    void 相同调用反复无进展应有界结束() {
        properties.setMaxAgentModelCalls(8);
        properties.setRepeatToolThreshold(3);

        for (int i = 0; i < 6; i++) {
            llmClient.enqueue(toolCallResponse("call_" + i, TOOL_QUERY, "{\"keyword\":\"篮球\"}"));
        }

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY)));

        assertEquals(AgentRunStatus.FAILED, result.getStatus());
        assertEquals("REPEAT_LIMIT", result.getErrorCode());
        assertEquals(3, queryExecutor.getInvocations(), "达到阈值后不应继续执行");
    }

    @Test
    void 输出被截断时不得执行不完整参数() {
        llmClient.enqueue(toolCallResponse("call_1", TOOL_QUERY, "{\"keyword\":\"篮", "length"));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY)));

        assertEquals(AgentRunStatus.FAILED, result.getStatus());
        assertEquals("FINISH_REASON_LENGTH", result.getErrorCode());
        assertEquals(0, queryExecutor.getInvocations());
    }

    @Test
    void 工具结果过长时应压缩且保留关键状态() {
        properties.setMaxToolResultChars(200);
        queryExecutor.setHandler(context -> AgentToolExecuteResult.success(
                "查询完成",
                Map.of("longText", "长".repeat(2000), "count", 3)
        ));

        llmClient.enqueue(toolCallResponse("call_1", TOOL_QUERY, "{\"keyword\":\"羽毛球\"}"));
        llmClient.enqueue(textResponse("已查询。"));

        AgentRunResult result = runtime.run(newContext(Set.of(TOOL_QUERY)));
        SessionMessage toolMessage = firstToolMessage(result);

        assertTrue(toolMessage.getContent().length() <= 400);
        assertTrue(toolMessage.getContent().contains("\"status\":\"SUCCESS\""));
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    private AgentRunContext newContext(Set<String> allowedTools) {
        AgentRunContext context = AgentRunContext.create(
                properties,
                1001L,
                "session-a",
                "run-1",
                "帮我查一下",
                null,
                new LinkedHashSet<>(allowedTools)
        );

        context.setMessages(new ArrayList<>(List.of(SessionMessage.system("你是测试助手"))));

        return context;
    }

    private SessionMessage firstToolMessage(AgentRunResult result) {
        return result.getNewMessages().stream()
                .filter(m -> "tool".equals(m.getRole()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有生成 tool 消息"));
    }

    private LlmToolCall toolCall(String id, String name, String arguments) {
        return LlmToolCall.builder()
                .id(id)
                .type("function")
                .function(LlmFunctionCall.builder().name(name).arguments(arguments).build())
                .build();
    }

    private LlmChatResponse textResponse(String content) {
        return response(content, null, "stop");
    }

    private LlmChatResponse toolCallResponse(String id, String name, String arguments) {
        return toolCallResponse(id, name, arguments, "tool_calls");
    }

    private LlmChatResponse toolCallResponse(String id, String name, String arguments, String finishReason) {
        return response(null, List.of(toolCall(id, name, arguments)), finishReason);
    }

    private LlmChatResponse toolCallsResponse(List<LlmToolCall> calls) {
        return response(null, calls, "tool_calls");
    }

    private LlmChatResponse response(String content, List<LlmToolCall> calls, String finishReason) {
        LlmResponseMessage message = new LlmResponseMessage();
        message.setRole("assistant");
        message.setContent(content);
        message.setToolCalls(calls);

        LlmChoice choice = new LlmChoice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(finishReason);

        LlmChatResponse response = new LlmChatResponse();
        response.setChoices(List.of(choice));

        return response;
    }

    /**
     * 按队列返回预设响应的 Fake 客户端。
     */
    static class FakeLlmClient implements LlmClient {

        private final Deque<LlmChatResponse> responses = new ArrayDeque<>();

        void enqueue(LlmChatResponse response) {
            responses.add(response);
        }

        @Override
        public LlmChatResponse chat(com.sau.gym.admin.agent.llm.LlmChatRequest request) {
            LlmChatResponse response = responses.poll();

            if (response == null) {
                throw new IllegalStateException("FakeLlmClient 没有更多响应");
            }

            return response;
        }
    }

    /**
     * 可配置行为的测试工具执行器。
     */
    static class TestToolExecutor extends AbstractGymAgentToolExecutor {

        private final String code;

        private final List<AgentToolParamDefinition> params;

        private Function<AgentToolExecuteContext, AgentToolExecuteResult> handler;

        private final AtomicInteger invocations = new AtomicInteger();

        TestToolExecutor(String code,
                         List<AgentToolParamDefinition> params,
                         Function<AgentToolExecuteContext, AgentToolExecuteResult> handler) {
            super(allowAllGuard());
            this.code = code;
            this.params = params;
            this.handler = handler;
        }

        void setHandler(Function<AgentToolExecuteContext, AgentToolExecuteResult> handler) {
            this.handler = handler;
        }

        int getInvocations() {
            return invocations.get();
        }

        @Override
        public String toolCode() {
            return code;
        }

        @Override
        public String toolName() {
            return code;
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public AgentRiskLevel riskLevel() {
            return AgentRiskLevel.LOW;
        }

        @Override
        public boolean needLogin() {
            return false;
        }

        @Override
        public boolean needConfirm() {
            return false;
        }

        @Override
        public List<AgentToolParamDefinition> paramDefinitions() {
            return params;
        }

        @Override
        protected AgentToolExecuteResult doExecute(AgentToolExecuteContext context) {
            invocations.incrementAndGet();
            return handler.apply(context);
        }

        private static AgentToolGuardService allowAllGuard() {
            return (userId, toolCode, toolName, riskLevel, requireLogin, requireConfirm, limitSeconds) -> null;
        }
    }
}

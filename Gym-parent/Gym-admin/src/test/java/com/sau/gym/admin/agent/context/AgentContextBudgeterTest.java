package com.sau.gym.admin.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.config.AgentHarnessProperties;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ModelBudgetInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ModelTraceInterceptor;
import com.sau.gym.admin.agent.llm.LlmChatResponse;
import com.sau.gym.admin.agent.llm.LlmChoice;
import com.sau.gym.admin.agent.llm.LlmClient;
import com.sau.gym.admin.agent.llm.LlmResponseMessage;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.runtime.AgentBudgetExceededException;
import com.sau.gym.admin.agent.runtime.AgentRunContext;
import com.sau.gym.admin.agent.service.AgentTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 作者:hfj
 * 功能:上下文自动摘要测试
 * 说明:
 * 覆盖摘要触发、保存边界、失败降级、tool_calls 配对保留与辅助预算计数。
 */
class AgentContextBudgeterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private final AgentHarnessProperties properties = new AgentHarnessProperties();

    private FakeLlmClient llmClient;

    private AgentTraceService traceService;

    @BeforeEach
    void setUp() {
        properties.setMaxAgentModelCalls(6);
        properties.setMaxAuxModelCalls(3);
        properties.setMaxTotalModelCalls(10);
        //预算设为 2400：5 轮胖历史约 2100 Token，高于触发阈值 1800，低于硬预算，用于隔离“摘要失败”场景。
        properties.setMaxContextTokens(2400);
        properties.setSummaryTriggerRatio(0.75D);
        properties.setSummaryKeepRecentTurns(2);
        properties.setSummaryMaxTokens(800);

        traceService = mock(AgentTraceService.class);
        llmClient = new FakeLlmClient();
    }

    private AgentContextBudgeter newBudgeter(AgentSessionSummarizer summarizer) {
        return new AgentContextBudgeter(summarizer, traceService, properties);
    }

    private AgentSessionSummarizer newRealSummarizer() {
        List<ModelInvocationInterceptor> interceptors = List.of(
                new ModelBudgetInterceptor(),
                new ModelTraceInterceptor(traceService)
        );

        return new AgentSessionSummarizer(interceptors, llmClient, properties);
    }

    @Test
    void 达到阈值时应生成摘要并移出旧轮次() {
        llmClient.enqueue(textResponse("用户想预约A场馆羽毛球场，已说明晚上7点到9点；曾询问停车规则；暂无未确认订单。"));

        AgentRunContext context = newContext();
        //5 轮历史，保留最近 2 轮，前 3 轮应被摘要。
        fillTurns(context.getMessages(), 5);

        AgentContextBudgeter budgeter = newBudgeter(newRealSummarizer());
        int estimated = budgeter.enforce(context, 100);

        assertNotNull(context.getSummary());
        assertTrue(context.getSummary().contains("A场馆"));
        assertEquals(1, context.getSummaryVersion());

        //最近 2 轮 + 当前输入保留，前 3 轮被移出。
        long userCount = context.getMessages().stream()
                .filter(message -> "user".equals(message.getRole()))
                .count();
        assertEquals(2, userCount);

        assertTrue(estimated > 0);
        assertEquals(1, context.getAuxModelCallCount(), "摘要调用必须计入辅助预算");
    }

    @Test
    void 摘要失败且未超预算时应保留原状态() {
        llmClient.enqueue(textResponse("   "));

        AgentRunContext context = newContext();
        fillTurns(context.getMessages(), 5);
        int beforeSize = context.getMessages().size();

        AgentContextBudgeter budgeter = newBudgeter(newRealSummarizer());
        budgeter.enforce(context, 100);

        assertNull(context.getSummary());
        assertEquals(0, context.getSummaryVersion());
        assertEquals(beforeSize, context.getMessages().size(), "摘要失败不能先删历史");
    }

    @Test
    void 摘要失败且无历史可裁剪仍超限时应明确终止() {
        llmClient.enqueue(textResponse("   "));

        AgentRunContext context = newContext();
        //小预算迫使降级裁剪；剩余单组仍超限时必须抛出上下文预算终态。
        context.setMaxContextTokens(120);
        fillTurns(context.getMessages(), 5);

        AgentContextBudgeter budgeter = newBudgeter(newRealSummarizer());

        AgentBudgetExceededException exception = assertThrows(AgentBudgetExceededException.class,
                () -> budgeter.enforce(context, 20));

        assertEquals("CONTEXT_LIMIT", exception.getReasonCode());
        assertNull(context.getSummary());

        //裁剪按完整轮次进行，tool_calls 与 tool 结果必须仍然配对。
        List<String> callIds = new ArrayList<>();
        context.getMessages().forEach(message -> {
            if (message.getToolCalls() != null) {
                message.getToolCalls().forEach(call -> callIds.add(call.getId()));
            }
        });

        callIds.forEach(callId -> assertTrue(
                context.getMessages().stream().anyMatch(message ->
                        "tool".equals(message.getRole()) && callId.equals(message.getToolCallId())),
                "tool 调用 " + callId + " 的结果不能被拆散"));
    }

    @Test
    void 仅当前超长输入无法裁剪时应终止且不调用模型() {
        AgentRunContext context = newContext();
        //只有当前一条超长输入：没有可摘要或裁剪的历史轮次。
        context.setMaxContextTokens(100);
        context.getMessages().add(SessionMessage.user("超长输入".repeat(300)));

        AgentContextBudgeter budgeter = newBudgeter(newRealSummarizer());

        AgentBudgetExceededException exception = assertThrows(AgentBudgetExceededException.class,
                () -> budgeter.enforce(context, 50));

        assertEquals("CONTEXT_LIMIT", exception.getReasonCode());
        assertFalse(llmClient.hasBeenCalled(), "达到硬上限时不应发起任何模型调用");
        assertEquals(0, context.getAuxModelCallCount());
    }

    @Test
    void 新摘要比旧摘要更长且仍超限时应继续终止() {
        //摘要返回超长内容，模拟“新摘要比旧摘要长”的情况。
        AgentSessionSummarizer longSummaryStub = new AgentSessionSummarizer(List.of(), llmClient, properties) {
            @Override
            public SummarizeResult summarize(AgentRunContext ctx, String previous, List<SessionMessage> turns) {
                return SummarizeResult.success("超长摘要".repeat(400));
            }
        };

        AgentRunContext context = newContext();
        context.setMaxContextTokens(600);
        fillTurns(context.getMessages(), 5);

        AgentContextBudgeter budgeter = newBudgeter(longSummaryStub);

        AgentBudgetExceededException exception = assertThrows(AgentBudgetExceededException.class,
                () -> budgeter.enforce(context, 50));

        assertEquals("CONTEXT_LIMIT", exception.getReasonCode());
        //摘要成功后历史被移出，但超长摘要本身导致仍超限。
        assertTrue(context.getMessages().size() <= 3 * 2, "降级裁剪应尽量保留最近轮次或清空历史");
    }

    @Test
    void 摘要文本超长或包含敏感表述时应判失败() {
        AgentSessionSummarizer stub = new AgentSessionSummarizer(List.of(), llmClient, properties) {
            @Override
            public SummarizeResult summarize(AgentRunContext ctx, String previous, List<SessionMessage> turns) {
                return SummarizeResult.failure("摘要超过长度预算");
            }
        };

        AgentRunContext context = newContext();
        fillTurns(context.getMessages(), 5);

        AgentContextBudgeter budgeter = newBudgeter(stub);
        budgeter.enforce(context, 100);

        assertNull(context.getSummary());
        assertEquals(5, countUserTurns(context.getMessages()));
    }

    @Test
    void 未达到阈值时不应触发摘要() {
        AgentRunContext context = newContext();
        fillTurns(context.getMessages(), 2);

        AgentContextBudgeter budgeter = newBudgeter(newRealSummarizer());
        budgeter.enforce(context, 50);

        assertNull(context.getSummary());
        assertEquals(0, context.getAuxModelCallCount(), "未达阈值不得调用摘要模型");
        assertFalse(llmClient.hasBeenCalled());
    }

    // ------------------------------------------------------------------
    // 构造辅助
    // ------------------------------------------------------------------

    private AgentRunContext newContext() {
        AgentRunContext context = AgentRunContext.create(
                properties,
                1001L,
                "session-a",
                "run-1",
                "当前输入",
                null,
                new LinkedHashSet<>()
        );
        context.setSystemPrompt("系统提示");
        context.setMessages(new ArrayList<>());
        return context;
    }

    /**
     * 填充 N 轮历史，每轮包含 user + assistant + tool_calls/tool 结果。
     */
    private void fillTurns(List<SessionMessage> messages, int turns) {
        for (int i = 0; i < turns; i++) {
            //repeat(55) ≈ 110 个中文字符，5 轮总估算约 2200 Token：
            //高于触发阈值 1800，低于硬预算 2400，用于隔离“摘要失败但未超预算”场景。
            String pad = "内容".repeat(55);

            messages.add(SessionMessage.user("第" + i + "轮：" + pad));
            messages.add(SessionMessage.assistant("第" + i + "轮回复：" + pad,
                    List.of(com.sau.gym.admin.agent.llm.LlmToolCall.builder()
                            .id("call_" + i)
                            .type("function")
                            .function(com.sau.gym.admin.agent.llm.LlmFunctionCall.builder()
                                    .name("tool_" + i)
                                    .arguments("{}")
                                    .build())
                            .build())));
            messages.add(SessionMessage.tool("call_" + i, "tool_" + i,
                    "{\"status\":\"SUCCESS\",\"data\":\"" + pad + "\"}"));
        }
    }

    private long countUserTurns(List<SessionMessage> messages) {
        return messages.stream().filter(message -> "user".equals(message.getRole())).count();
    }

    private LlmChatResponse textResponse(String content) {
        LlmResponseMessage message = new LlmResponseMessage();
        message.setRole("assistant");
        message.setContent(content);

        LlmChoice choice = new LlmChoice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason("stop");

        LlmChatResponse response = new LlmChatResponse();
        response.setChoices(List.of(choice));
        return response;
    }

    static class FakeLlmClient implements LlmClient {

        private final Deque<LlmChatResponse> responses = new ArrayDeque<>();

        private boolean called = false;

        void enqueue(LlmChatResponse response) {
            responses.add(response);
        }

        boolean hasBeenCalled() {
            return called;
        }

        @Override
        public LlmChatResponse chat(com.sau.gym.admin.agent.llm.LlmChatRequest request) {
            called = true;
            LlmChatResponse response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("FakeLlmClient 没有更多响应");
            }
            return response;
        }
    }
}

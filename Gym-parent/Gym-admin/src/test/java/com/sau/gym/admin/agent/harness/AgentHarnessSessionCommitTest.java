package com.sau.gym.admin.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.config.AgentHarnessProperties;
import com.sau.gym.admin.agent.context.AgentContextBudgeter;
import com.sau.gym.admin.agent.context.AgentSessionSummarizer;
import com.sau.gym.admin.agent.context.AgentSystemPromptBuilder;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ModelBudgetInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ModelTraceInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ToolArgumentInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ToolAuthorizationInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ToolBudgetInterceptor;
import com.sau.gym.admin.agent.harness.interceptor.impl.ToolTraceInterceptor;
import com.sau.gym.admin.agent.intent.GymIntentRouter;
import com.sau.gym.admin.agent.llm.LlmChatResponse;
import com.sau.gym.admin.agent.llm.LlmChoice;
import com.sau.gym.admin.agent.llm.LlmClient;
import com.sau.gym.admin.agent.llm.LlmResponseMessage;
import com.sau.gym.admin.agent.memory.model.SessionHistory;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.memory.service.AgentSessionMessageStore;
import com.sau.gym.admin.agent.rewrite.service.QuestionRewriteService;
import com.sau.gym.admin.agent.runtime.AgentRunResult;
import com.sau.gym.admin.agent.runtime.AgentRunStatus;
import com.sau.gym.admin.agent.runtime.ReActRuntime;
import com.sau.gym.admin.agent.service.AgentContextEnhanceService;
import com.sau.gym.admin.agent.service.AgentDirectRouteService;
import com.sau.gym.admin.agent.service.AgentSafetyService;
import com.sau.gym.admin.agent.service.AgentTraceService;
import com.sau.gym.admin.agent.sop.AgentSopCatalog;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.AgentSessionLockService;
import com.sau.gym.admin.agent.store.SessionWriteResult;
import com.sau.gym.admin.agent.store.SessionWriteTicket;
import com.sau.gym.admin.agent.tool.AgentToolSelector;
import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import com.sau.gym.admin.agent.workflow.AgentConfirmationWorkflow;
import com.sau.gym.admin.agent.workflow.AgentWorkflowRouter;
import com.sau.gym.admin.agent.workflow.WorkflowDecision;
import com.sau.gym.admin.agent.workflow.WorkflowType;
import com.sau.gym.model.dto.agent.AgentChatDto;
import com.sau.gym.admin.agent.harness.AgentHarnessRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:终态历史一致性回归（第三轮 P0-A）
 * 说明:
 * 穿过 Runtime → Harness → 持久化快照的真实路径：
 * 预算终止时，最终 assistant 回复必须进入真实保存的历史，
 * 且用户消息与最终回复都只出现一次；仅断言 newMessages 不算完成。
 */
class AgentHarnessSessionCommitTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private AgentHarnessProperties properties;

    private AgentSessionMessageStore messageStore;

    private AgentWorkflowRouter workflowRouter;

    private AgentToolSelector toolSelector;

    private DefaultGymAgentHarness harness;

    private final Deque<LlmChatResponse> responses = new ArrayDeque<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new AgentHarnessProperties();
        //预算为零：本轮不应发起任何模型调用，直接预算终止。
        properties.setMaxAgentModelCalls(0);

        messageStore = mock(AgentSessionMessageStore.class);
        workflowRouter = mock(AgentWorkflowRouter.class);
        toolSelector = mock(AgentToolSelector.class);

        when(messageStore.loadHistory(any(), anyString())).thenReturn(new SessionHistory());
        when(messageStore.saveHistoryIfHolder(any(), anyString(), any(), any(SessionWriteTicket.class)))
                .thenReturn(SessionWriteResult.SAVED);
        when(toolSelector.select(any(), anyString())).thenReturn(new LinkedHashSet<>());
        when(workflowRouter.decide(any(), any()))
                .thenReturn(WorkflowDecision.of(WorkflowType.REACT, "test"));

        AgentSafetyService safetyService = mock(AgentSafetyService.class);
        when(safetyService.checkUserMessage(any(), anyString()))
                .thenReturn(com.sau.gym.admin.agent.security.AgentSafetyCheckResult.allow());

        AgentContextEnhanceService contextEnhanceService = mock(AgentContextEnhanceService.class);
        when(contextEnhanceService.prepareBeforeAgent(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new com.sau.gym.admin.agent.memory.model.AgentBusinessContext());

        AgentSessionLockService lockService = mock(AgentSessionLockService.class);
        AgentSessionLockService.LockHandle lock = mock(AgentSessionLockService.LockHandle.class);
        when(lockService.tryLock(any(), anyString(), any())).thenReturn(lock);
        when(lock.isLeaseValid()).thenReturn(true);
        when(lock.toTicket()).thenReturn(SessionWriteTicket.of(
                "agent:session:lock:user:1:session:session-a", "token"));

        AgentTraceService traceService = mock(AgentTraceService.class);

        //真实 ReAct 运行时（预算拦截器在真实链路上生效）。
        List<ModelInvocationInterceptor> modelInterceptors = List.of(
                new ModelBudgetInterceptor(),
                new ModelTraceInterceptor(traceService)
        );

        List<ToolInvocationInterceptor> toolInterceptors = List.of(
                new ToolBudgetInterceptor(objectMapper),
                new ToolAuthorizationInterceptor(mock(GymAgentToolRegistry.class)),
                new ToolArgumentInterceptor(mock(GymAgentToolRegistry.class)),
                new ToolTraceInterceptor(traceService, objectMapper)
        );

        LlmClient llmClient = request -> {
            LlmChatResponse response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("不应发起模型调用");
            }
            return response;
        };

        ReActRuntime reActRuntime = new com.sau.gym.admin.agent.runtime.DefaultReActRuntime(
                modelInterceptors,
                toolInterceptors,
                mock(GymAgentToolRegistry.class),
                new com.sau.gym.admin.agent.tool.AgentToolSchemaBuilder(mock(GymAgentToolRegistry.class)),
                new com.sau.gym.admin.agent.tool.ToolResultCompactor(objectMapper),
                new AgentContextBudgeter(
                        mock(AgentSessionSummarizer.class), traceService, properties),
                objectMapper,
                llmClient,
                traceService
        );

        harness = new DefaultGymAgentHarness(
                properties,
                messageStore,
                lockService,
                safetyService,
                contextEnhanceService,
                mock(GymIntentRouter.class),
                mock(QuestionRewriteService.class),
                workflowRouter,
                mock(AgentDirectRouteService.class),
                mock(AgentConfirmationWorkflow.class),
                toolSelector,
                mock(AgentSystemPromptBuilder.class),
                reActRuntime,
                traceService,
                mock(AgentDraftStore.class),
                mock(AgentSopCatalog.class),
                mock(com.sau.gym.admin.agent.run.AgentRunStore.class),
                mock(com.sau.gym.admin.agent.snapshot.AgentResponseAssembler.class),
                objectMapper
        );
    }

    @Test
    void 预算终止的最终回复应进入真实保存的历史且只出现一次() {
        AgentHarnessRequest request = AgentHarnessRequest.builder()
                .userId(1L)
                .sessionId("session-a")
                .runId("run-1")
                .message("帮我查一下")
                .chatDto(new AgentChatDto())
                .build();

        AgentRunResult result = harness.run(request);

        assertEquals(AgentRunStatus.BUDGET_EXCEEDED, result.getStatus());
        assertTrue(responses.isEmpty(), "预算为零时不得发起任何模型调用");

        ArgumentCaptor<SessionHistory> historyCaptor = ArgumentCaptor.forClass(SessionHistory.class);
        verify(messageStore).saveHistoryIfHolder(any(), anyString(), historyCaptor.capture(),
                any(SessionWriteTicket.class));

        SessionHistory saved = historyCaptor.getValue();
        List<SessionMessage> messages = saved.getMessages();

        //用户消息只出现一次。
        long userCount = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .count();
        assertEquals(1, userCount);

        //最终 assistant 正文回复只出现一次（含可读正文，工具消息不算）。
        long finalReplyCount = messages.stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .filter(m -> m.getToolCalls() == null || m.getToolCalls().isEmpty())
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .count();
        assertEquals(1, finalReplyCount, "终态回复必须进入真实历史且只有一条");

        //历史内容与返回给前端的文字一致。
        String savedReply = messages.stream()
                .filter(m -> "assistant".equals(m.getRole())
                        && (m.getToolCalls() == null || m.getToolCalls().isEmpty()))
                .map(SessionMessage::getContent)
                .findFirst()
                .orElse(null);
        assertEquals(result.getReplyText(), savedReply);

        //展示轮次同步写入，供恢复接口使用。
        assertEquals(1, saved.getDisplayTurns().size());
        assertEquals("run-1", saved.getDisplayTurns().get(0).getTurnId());
        assertEquals(result.getReplyText(), saved.getDisplayTurns().get(0).getReplyText());
    }

    private Set<String> setOf(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}

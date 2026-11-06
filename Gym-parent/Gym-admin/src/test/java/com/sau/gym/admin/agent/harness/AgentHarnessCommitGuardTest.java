package com.sau.gym.admin.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.config.AgentHarnessProperties;
import com.sau.gym.admin.agent.context.AgentSystemPromptBuilder;
import com.sau.gym.admin.agent.intent.GymIntentRouter;
import com.sau.gym.admin.agent.memory.model.SessionHistory;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.memory.service.AgentSessionMessageStore;
import com.sau.gym.admin.agent.rewrite.service.QuestionRewriteService;
import com.sau.gym.admin.agent.runtime.AgentRunResult;
import com.sau.gym.admin.agent.runtime.ReActRuntime;
import com.sau.gym.admin.agent.service.AgentContextEnhanceService;
import com.sau.gym.admin.agent.service.AgentDirectRouteService;
import com.sau.gym.admin.agent.service.AgentSafetyService;
import com.sau.gym.admin.agent.service.AgentTraceService;
import com.sau.gym.admin.agent.sop.AgentSopCatalog;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.AgentSessionLockService;
import com.sau.gym.admin.agent.tool.AgentToolSelector;
import com.sau.gym.admin.agent.workflow.AgentConfirmationWorkflow;
import com.sau.gym.admin.agent.workflow.AgentWorkflowRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:会话提交锁保护测试（R4 生产接线验证）
 * 说明:
 * 验证 Harness 提交阶段使用真实 LockHandle 租约状态：
 * 锁失效（过期或被新持有者接管）的旧请求不得覆盖新状态。
 */
class AgentHarnessCommitGuardTest {

    private AgentSessionMessageStore messageStore;

    private DefaultGymAgentHarness harness;

    private DefaultGymAgentHarness.RunSession session;

    private AgentSessionLockService.LockHandle lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        messageStore = mock(AgentSessionMessageStore.class);

        harness = new DefaultGymAgentHarness(
                new AgentHarnessProperties(),
                messageStore,
                mock(AgentSessionLockService.class),
                mock(AgentSafetyService.class),
                mock(AgentContextEnhanceService.class),
                mock(GymIntentRouter.class),
                mock(QuestionRewriteService.class),
                mock(AgentWorkflowRouter.class),
                mock(AgentDirectRouteService.class),
                mock(AgentConfirmationWorkflow.class),
                mock(AgentToolSelector.class),
                mock(AgentSystemPromptBuilder.class),
                mock(ReActRuntime.class),
                mock(AgentTraceService.class),
                mock(AgentDraftStore.class),
                mock(AgentSopCatalog.class),
                new ObjectMapper()
        );

        lock = mock(AgentSessionLockService.LockHandle.class);

        session = new DefaultGymAgentHarness.RunSession();
        session.lockHandle = lock;
        session.pendingHistory = new SessionHistory();
        session.pendingHistory.setMessages(List.of(
                SessionMessage.user("旧请求消息"),
                SessionMessage.assistant("旧请求回复")
        ));
    }

    @Test
    void 锁失效后不得提交会话历史() {
        when(lock.isLeaseValid()).thenReturn(false);

        boolean committed = harness.commitSessionHistory(1L, "session-a", session,
                AgentRunResult.completed("旧请求回复"));

        assertFalse(committed);
        verify(messageStore, never()).saveHistory(any(), anyString(), any());
    }

    @Test
    void 锁有效时应正常提交会话历史() {
        when(lock.isLeaseValid()).thenReturn(true);
        when(messageStore.saveHistory(any(), anyString(), any())).thenReturn(true);

        boolean committed = harness.commitSessionHistory(1L, "session-a", session,
                AgentRunResult.completed("回复"));

        assertTrue(committed);
        verify(messageStore).saveHistory(1L, "session-a", session.pendingHistory);
    }
}

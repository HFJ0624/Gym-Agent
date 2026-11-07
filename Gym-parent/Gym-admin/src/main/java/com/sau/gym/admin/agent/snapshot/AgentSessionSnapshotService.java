package com.sau.gym.admin.agent.snapshot;

import com.sau.gym.admin.agent.memory.model.DisplayTurn;
import com.sau.gym.admin.agent.memory.model.SessionHistory;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.memory.service.AgentSessionMessageStore;
import com.sau.gym.admin.agent.run.AgentRunLookup;
import com.sau.gym.admin.agent.run.AgentRunRecord;
import com.sau.gym.admin.agent.run.AgentRunStore;
import com.sau.gym.admin.agent.service.AgentTraceService;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.PendingDraft;
import com.sau.gym.admin.agent.util.AgentSessionIdUtil;
import com.sau.gym.model.dto.agent.AgentSessionSnapshot;
import com.sau.gym.model.vo.agent.AgentRunInfo;
import com.sau.gym.model.vo.agent.AgentTurnVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 作者:hfj
 * 功能:会话恢复快照服务（只读）
 * 说明:
 * 1. 一个接口返回最近展示对话、最新运行信息与当前有效待办；
 *    不触发模型调用，不执行任何业务写操作，不延长草稿 TTL。
 * 2. 展示轮次与协议消息区分：优先读展示字段，旧快照按协议消息受控提取普通文本；
 *    不伪造旧 sources、卡片和业务结果。
 * 3. 运行仍为 RUNNING 时，按 Redis 锁存在性条件写识别中断；
 *    状态存储不可用时 runAvailable=false，与“无会话”区分。
 * 4. 身份与数据范围由调用方（Controller）保证：可信 userId + 合法 sessionId。
 */
@Service
public class AgentSessionSnapshotService {

    /**
     * 最多返回的展示轮次。
     */
    private static final int MAX_TURNS = 20;

    private static final Logger log = LoggerFactory.getLogger(AgentSessionSnapshotService.class);

    private final AgentSessionMessageStore messageStore;

    private final AgentRunStore agentRunStore;

    private final AgentDraftStore draftStore;

    private final AgentResponseAssembler responseAssembler;

    private final AgentTraceService traceService;

    public AgentSessionSnapshotService(AgentSessionMessageStore messageStore,
                                       AgentRunStore agentRunStore,
                                       AgentDraftStore draftStore,
                                       AgentResponseAssembler responseAssembler,
                                       AgentTraceService traceService) {
        this.messageStore = messageStore;
        this.agentRunStore = agentRunStore;
        this.draftStore = draftStore;
        this.responseAssembler = responseAssembler;
        this.traceService = traceService;
    }

    /**
     * 构建会话恢复快照。
     *
     * @param userId    可信用户ID（必须已登录）
     * @param sessionId 会话ID
     */
    public AgentSessionSnapshot buildSnapshot(Long userId, String sessionId) {
        AgentSessionSnapshot snapshot = new AgentSessionSnapshot();

        String realSessionId = AgentSessionIdUtil.normalize(sessionId);
        snapshot.setSessionId(realSessionId);

        //1. 历史快照；读取失败必须如实标记，不能当作空会话。
        SessionHistory history = messageStore.loadHistory(userId, realSessionId);

        if (history.isLoadFailed()) {
            snapshot.setLoadFailed(true);
            trace("SESSION_RESTORE", "会话历史读取失败", realSessionId, null, "FAILED",
                    history.getLoadError(), 0L);
        }

        //2. 展示轮次：优先展示字段，旧快照按协议消息受控提取。
        List<DisplayTurn> turns = history.getDisplayTurns() == null
                ? new ArrayList<>()
                : history.getDisplayTurns();

        boolean legacyTurns = turns.isEmpty();
        if (legacyTurns) {
            turns = extractLegacyTurns(history.getMessages());
        }

        //稳定 turnId 去重，保留较新的轮次；不依赖数组下标或正文比较。
        List<AgentTurnVO> turnVOs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int i = turns.size() - 1; i >= 0 && turnVOs.size() < MAX_TURNS; i--) {
            DisplayTurn turn = turns.get(i);

            if (turn == null || !StringUtils.hasText(turn.getTurnId()) || !seen.add(turn.getTurnId())) {
                continue;
            }

            AgentTurnVO vo = new AgentTurnVO();
            vo.setTurnId(turn.getTurnId());
            vo.setUserText(turn.getUserText());
            vo.setReplyText(turn.getReplyText());
            vo.setStatus(turn.getStatus());
            vo.setSources(turn.getSources() == null ? new ArrayList<>() : turn.getSources());
            vo.setCreateTime(turn.getCreateTime());

            turnVOs.add(vo);
        }

        //恢复展示按时间升序返回。
        java.util.Collections.reverse(turnVOs);
        snapshot.setTurns(turnVOs);

        //3. 运行状态：RUNNING 时按锁存在性条件写识别中断。
        AgentRunLookup lookup = agentRunStore.getLatest(userId, realSessionId);
        snapshot.setRunAvailable(lookup.isAvailable());

        if (lookup.isAvailable() && lookup.getRecord() != null) {
            AgentRunRecord record = lookup.getRecord();

            if (AgentRunStatusName.RUNNING.equals(record.getStatus())) {
                boolean marked = agentRunStore.markInterruptedIfRunning(userId, realSessionId);

                if (marked) {
                    record.setStatus(AgentRunStatusName.INTERRUPTED);
                    trace("RUN_INTERRUPTED", "识别到运行中断", realSessionId,
                            "runId=" + record.getRunId(), "SUCCESS", null, 0L);
                }
            }

            AgentRunInfo runInfo = new AgentRunInfo();
            runInfo.setRunId(record.getRunId());
            runInfo.setStatus(record.getStatus());
            runInfo.setStage(record.getStage());
            runInfo.setErrorCode(record.getErrorCode());
            runInfo.setSessionCommitted(record.getSessionCommitted());
            runInfo.setBusinessId(record.getBusinessId());
            runInfo.setUpdateTime(record.getUpdateTime());
            snapshot.setRun(runInfo);
        }

        //4. 当前有效待办：实时读取真实草稿；只读，不延长 TTL、不重签确认码。
        try {
            PendingDraft draft = draftStore.get(userId, realSessionId);

            if (draft != null) {
                snapshot.getCards().add(responseAssembler.buildCard(draft));
                snapshot.getActions().addAll(responseAssembler.buildActions(draft));
            }
        } catch (Exception e) {
            log.warn("[AgentSnapshot] 读取当前草稿失败: {}", e.getMessage());
        }

        trace("SESSION_RESTORE", "会话快照构建完成", realSessionId,
                "turns=" + turnVOs.size()
                        + ", runAvailable=" + snapshot.isRunAvailable()
                        + ", loadFailed=" + snapshot.isLoadFailed(),
                "SUCCESS", null, 0L);

        return snapshot;
    }

    /**
     * 旧快照受控提取：只提取普通 user 文本与最终 assistant 正文，
     * 不伪造 sources、卡片和业务结果。
     */
    private List<DisplayTurn> extractLegacyTurns(List<SessionMessage> messages) {
        List<DisplayTurn> turns = new ArrayList<>();

        if (messages == null || messages.isEmpty()) {
            return turns;
        }

        String currentUser = null;
        String lastAssistant = null;

        for (SessionMessage message : messages) {
            if (message == null || !StringUtils.hasText(message.getRole())) {
                continue;
            }

            if ("user".equals(message.getRole())) {
                if (currentUser != null && lastAssistant != null) {
                    turns.add(buildLegacyTurn(currentUser, lastAssistant, turns.size()));
                }
                currentUser = message.getContent();
                lastAssistant = null;
                continue;
            }

            if ("assistant".equals(message.getRole())
                    && (message.getToolCalls() == null || message.getToolCalls().isEmpty())
                    && StringUtils.hasText(message.getContent())) {
                lastAssistant = message.getContent();
            }
        }

        if (currentUser != null && lastAssistant != null) {
            turns.add(buildLegacyTurn(currentUser, lastAssistant, turns.size()));
        }

        return turns;
    }

    /**
     * 构造旧数据展示轮次。
     */
    private DisplayTurn buildLegacyTurn(String userText, String replyText, int index) {
        return DisplayTurn.builder()
                .turnId("legacy-" + index)
                .runId("legacy-" + index)
                .userText(userText)
                .replyText(replyText)
                .status(null)
                .sources(new ArrayList<>())
                .createTime(System.currentTimeMillis())
                .build();
    }

    /**
     * AgentRunStatus 常量名（避免额外依赖）。
     */
    private static final class AgentRunStatusName {
        private static final String RUNNING = "RUNNING";
        private static final String INTERRUPTED = "INTERRUPTED";
    }

    /**
     * 安全写 Trace。
     */
    private void trace(String stepType, String stepName, String input, String output,
                       String status, String error, Long costMs) {
        try {
            traceService.addStep(stepType, stepName, input, output, status, error, costMs);
        } catch (Exception e) {
            log.warn("[AgentSnapshot] Trace 写入失败: {}", e.getMessage());
        }
    }
}

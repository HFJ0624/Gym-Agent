package com.sau.gym.admin.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.config.AgentHarnessProperties;
import com.sau.gym.admin.agent.context.AgentSystemPromptBuilder;
import com.sau.gym.admin.agent.intent.GymIntentRouter;
import com.sau.gym.admin.agent.intent.IntentRouteRequest;
import com.sau.gym.admin.agent.intent.IntentRouteResult;
import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.admin.agent.memory.model.SessionHistory;
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
import com.sau.gym.admin.agent.sop.AgentSopCatalog;
import com.sau.gym.admin.agent.sop.SopDefinition;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.AgentSessionLockService;
import com.sau.gym.admin.agent.store.PendingDraft;
import com.sau.gym.admin.agent.tool.AgentToolSelector;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.util.AgentSessionIdUtil;
import com.sau.gym.admin.agent.workflow.AgentConfirmationWorkflow;
import com.sau.gym.admin.agent.workflow.AgentWorkflowRouter;
import com.sau.gym.admin.agent.workflow.WorkflowDecision;
import com.sau.gym.admin.agent.workflow.WorkflowType;
import com.sau.gym.admin.enums.PendingDraftType;
import com.sau.gym.model.dto.agent.AgentChatDto;
import com.sau.gym.model.vo.agent.AgentSourceVO;
import com.sau.gym.admin.rag.retrieval.EvidenceRegistry;
import com.sau.gym.admin.rag.retrieval.KnowledgeEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作者:hfj
 * 功能:Agent Harness 默认实现
 * 职责:
 * 1. 会话串行控制与历史快照加载（消息 + 摘要）。
 * 2. 输入安全检查。
 * 3. 业务上下文准备、意图识别、问题重写。
 * 4. 选择确认/取消/追问/直达/ReAct 流程；ReAct 场景按需加载服务端 SOP。
 * 5. 统一提交会话快照并组装结构化回复（来源/卡片/动作）。
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

    private final AgentSopCatalog sopCatalog;

    private final com.sau.gym.admin.agent.run.AgentRunStore agentRunStore;

    private final com.sau.gym.admin.agent.snapshot.AgentResponseAssembler responseAssembler;

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
                                  AgentSopCatalog sopCatalog,
                                  com.sau.gym.admin.agent.run.AgentRunStore agentRunStore,
                                  com.sau.gym.admin.agent.snapshot.AgentResponseAssembler responseAssembler,
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
        this.sopCatalog = sopCatalog;
        this.agentRunStore = agentRunStore;
        this.responseAssembler = responseAssembler;
        this.objectMapper = objectMapper;
    }

    /**
     * 单次请求的会话载体。
     * 保存历史快照、ReAct 上下文与真实锁句柄，供结束阶段统一校验与提交。
     */
    static class RunSession {
        SessionHistory snapshot;
        AgentRunContext reactContext;
        AgentSessionLockService.LockHandle lockHandle;
        SessionHistory pendingHistory;
        com.sau.gym.admin.agent.store.SessionWriteTicket writeTicket;
    }

    /**
     * 提交会话历史快照。
     * 真实锁租约失效（过期或被新请求接管）时跳过提交，避免旧请求覆盖新状态。
     *
     * @return 是否实际提交成功
     */
    boolean commitSessionHistory(Long userId,
                                 String sessionId,
                                 RunSession session,
                                 AgentRunResult result) {
        //读取失败的快照不可信：此时提交会用未知状态覆盖原记录，必须跳过。
        if (session.snapshot != null && session.snapshot.isLoadFailed()) {
            trace("SESSION_COMMIT", "历史读取失败，跳过会话提交", sessionId, null, "FAILED",
                    "history load failed, refuse to overwrite with unknown state", 0L);
            return false;
        }

        if (session.lockHandle != null && !session.lockHandle.isLeaseValid()) {
            trace("SESSION_COMMIT", "会话锁已失效，跳过会话提交", sessionId, null, "FAILED",
                    "lock lease invalid, skip commit to avoid overwriting newer session state", 0L);
            return false;
        }

        //Redis 原子校验持有令牌后写入，关闭检查与写入之间的覆盖窗口。
        com.sau.gym.admin.agent.store.SessionWriteResult writeResult =
                messageStore.saveHistoryIfHolder(userId, sessionId, session.pendingHistory, session.writeTicket);

        if (writeResult != com.sau.gym.admin.agent.store.SessionWriteResult.SAVED) {
            trace("SESSION_COMMIT", "会话提交未成功", sessionId, null, "FAILED",
                    "protected write result=" + writeResult, 0L);
            return false;
        }

        return true;
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

        RunSession session = new RunSession();
        session.lockHandle = lock;
        session.writeTicket = lock.toTicket();

        try {
            trace("HARNESS_START", "开始处理本轮请求",
                    "sessionId=" + sessionId + ", runId=" + runId, null, "SUCCESS", null, 0L);

            //1. 加载会话历史快照（消息 + 摘要）。
            session.snapshot = messageStore.loadHistory(userId, sessionId);

            //1.1 建立持久运行记录；失败只降级，不阻断对话。
            boolean runTracked = agentRunStore.startRunning(userId, sessionId, runId,
                    com.sau.gym.admin.agent.run.AgentRunStage.CONSULTING.name(), session.writeTicket);
            trace("RUN_START", "建立运行记录",
                    "sessionId=" + sessionId + ", runId=" + runId,
                    "tracked=" + runTracked, "SUCCESS", null, 0L);

            //2. 输入安全检查。
            AgentSafetyCheckResult safetyResult = safetyService.checkUserMessage(userId, message);

            if (!safetyResult.isAllowed()) {
                AgentRunResult result = AgentRunResult.completed(safetyResult.getReason());
                trace("SAFETY_CHECK", "用户输入安全检查未通过", message,
                        result.getReplyText(), "FAILED", safetyResult.getReason(), 0L);
                return finish(result, request, sessionId, session);
            }

            //3. 业务上下文准备。
            AgentBusinessContext businessContext = contextEnhanceService.prepareBeforeAgent(
                    userId, sessionId, message, request.getChatDto(), session.writeTicket
            );

            //4. 意图识别与问题重写，属于辅助信息，失败不应阻断主流程。
            IntentRouteResult intentResult = routeIntent(userId, message, businessContext, request.getChatDto());
            QuestionRewriteResult rewriteResult = rewrite(userId, message, intentResult, businessContext,
                    request.getChatDto());

            //5. 流程选择。
            WorkflowDecision decision = workflowRouter.decide(message, intentResult);

            trace("WORKFLOW_SELECT", "选择流程: " + decision.getType(),
                    "sessionId=" + sessionId + ", runId=" + runId + ", reason=" + decision.getReason(),
                    null, "SUCCESS", null, 0L);

            AgentRunResult result = dispatch(decision, request, sessionId, businessContext,
                    intentResult, rewriteResult, session);

            //6. 草稿生成后把准确槽位回写业务上下文。
            refreshContext(userId, sessionId, session.writeTicket);

            return finish(result, request, sessionId, session);

        } catch (Exception e) {
            //不向用户暴露堆栈，只记录可观察信息。
            log.error("[AgentHarness] 处理失败, sessionId={}", sessionId, e);

            trace("HARNESS_FAILED", "本轮处理异常", message, null, "FAILED", e.getMessage(),
                    System.currentTimeMillis() - start);

            AgentRunResult result = AgentRunResult.failed("AI 服务暂时不可用，请稍后再试。", "HARNESS_ERROR");
            result.setNewMessages(buildSimpleMessages(message, result.getReplyText()));

            commitQuietly(userId, sessionId, result, session);
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
                                    QuestionRewriteResult rewriteResult,
                                    RunSession session) {

        Long userId = request.getUserId();
        String message = request.getMessage() == null ? "" : request.getMessage().trim();

        //确认/取消属于可变状态操作：锁失效后不再执行，避免旧请求覆盖新请求的待办。
        if (session.lockHandle != null && !session.lockHandle.isLeaseValid()) {
            return AgentRunResult.failed("会话状态已失效，请重新发送请求。", "LOCK_LOST");
        }

        //进入确认办理阶段：恢复查询据此区分“可安全重问”与“结果需核实”。
        agentRunStore.updateStage(request.getUserId(), sessionId, request.getRunId(),
                com.sau.gym.admin.agent.run.AgentRunStage.CONFIRMING.name(), session.writeTicket);

        return switch (decision.getType()) {
            case CONFIRMATION -> confirmationWorkflow.handle(decision, userId, sessionId, message, session.writeTicket);

            case CANCEL_BOOKING_DRAFT -> confirmationWorkflow.handleCancelBookingDraft(
                    String.valueOf(decision.getPayload().get("bookingId")),
                    userId,
                    sessionId,
                    message,
                    session.writeTicket
            );

            case CLARIFY -> {
                Object question = decision.getPayload().get("question");
                yield AgentRunResult.clarification(
                        question == null ? "请补充更多信息后再试。" : String.valueOf(question)
                );
            }

            case DIRECT_ROUTE -> runDirectRoute(request, sessionId, businessContext, message,
                    intentResult, rewriteResult, session);

            case REACT -> runReact(request, sessionId, businessContext, intentResult, rewriteResult, session);
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
                                          QuestionRewriteResult rewriteResult,
                                          RunSession session) {

        Long userId = request.getUserId();
        AgentChatDto dto = request.getChatDto();

        Long venueId = contextEnhanceService.getEffectiveVenueId(dto, businessContext);
        Long courtId = contextEnhanceService.getEffectiveCourtId(dto, businessContext);

        AgentToolExecuteResult toolResult = directRouteService.tryHandle(
                userId, sessionId, message, venueId, courtId, businessContext
        );

        if (toolResult == null) {
            return runReact(request, sessionId, businessContext, intentResult, rewriteResult, session);
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
     * 按场景加载服务端 SOP，并与实际授权工具求交集。
     */
    private AgentRunResult runReact(AgentHarnessRequest request,
                                    String sessionId,
                                    AgentBusinessContext businessContext,
                                    IntentRouteResult intentResult,
                                    QuestionRewriteResult rewriteResult,
                                    RunSession session) {

        Long userId = request.getUserId();
        String message = request.getMessage() == null ? "" : request.getMessage().trim();

        LinkedHashSet<String> allowedTools = toolSelector.select(
                intentResult == null ? null : intentResult.getIntent(),
                message
        );

        //场景选择与工具选择统一到一次规划结果：
        //1. ToolSelector 先给出规划工具（含组合请求的关键词补充）。
        //2. SOP 按意图、真实待办草稿类型和明确办理型措辞选择（可多选）。
        //3. 组合场景取相关 SOP 工具的受控并集后求交集，单个 SOP 不能删掉其他子场景的必要工具；
        //   纯咨询措辞（如“取消规则是什么”）不会因此进入真实取消办理。
        String intentName = intentResult == null || intentResult.getIntent() == null
                ? null
                : intentResult.getIntent().name();

        String draftTypeName = resolveDraftTypeName(userId, sessionId);
        List<SopDefinition> selectedSops = sopCatalog.selectSops(intentName, message, draftTypeName);

        String sopPrompt = null;

        if (!selectedSops.isEmpty()) {
            Set<String> allowedBySops = new LinkedHashSet<>();
            StringBuilder sopText = new StringBuilder();

            for (SopDefinition selected : selectedSops) {
                if (selected.getAllowedTools() != null) {
                    allowedBySops.addAll(selected.getAllowedTools());
                }

                String body = sopCatalog.loadBody(selected);

                if (StringUtils.hasText(body)) {
                    if (sopText.length() > 0) {
                        sopText.append("\n\n");
                    }
                    sopText.append("SOP: ").append(selected.getName())
                            .append(" v").append(selected.getVersion()).append("\n")
                            .append(body);
                }
            }

            if (!allowedBySops.isEmpty()) {
                allowedTools.retainAll(allowedBySops);
            }

            if (sopText.length() > 0) {
                sopPrompt = sopText.toString();
            }
        }

        String sopNames = selectedSops.stream()
                .map(selected -> selected.getName() + " v" + selected.getVersion())
                .collect(java.util.stream.Collectors.joining(","));

        trace("SOP_SELECT", "场景 SOP 选择",
                "intent=" + intentName + ", draftType=" + draftTypeName,
                (sopNames.isEmpty() ? "none" : sopNames) + ", tools=" + allowedTools,
                "SUCCESS", null, 0L);

        trace("TOOLS_ALLOWED", "本轮授权工具",
                "sessionId=" + sessionId, String.join(",", allowedTools), "SUCCESS", null, 0L);

        String systemPrompt = promptBuilder.build(
                contextEnhanceService.buildContextPrompt(businessContext),
                buildPagePrompt(request.getChatDto(), businessContext),
                intentResult == null ? null : intentResult.getRoutePrompt(),
                rewriteResult == null ? null : rewriteResult.getRewritePrompt(),
                sopPrompt,
                allowedTools,
                hasPendingDraft(userId, sessionId)
        );

        AgentRunContext runContext = AgentRunContext.create(
                properties,
                userId,
                sessionId,
                request.getRunId(),
                message,
                businessContext,
                allowedTools
        );

        //系统提示与摘要不持久化在 messages 中，由运行时在每次请求时拼接。
        runContext.setSystemPrompt(systemPrompt);
        runContext.setSummary(session.snapshot == null ? null : session.snapshot.getSummary());
        runContext.setSummaryVersion(session.snapshot == null ? 0 : session.snapshot.getSummaryVersion());
        runContext.setEvidenceRegistry(new EvidenceRegistry());
        runContext.setMessages(new ArrayList<>(session.snapshot == null
                ? List.of()
                : session.snapshot.getMessages()));

        //把真实锁租约状态接线到运行时：
        //锁可能在模型请求期间丢失，运行时每次派发前都会通过该提供器重新检查。
        if (session.lockHandle != null) {
            runContext.setLockValidSupplier(session.lockHandle::isLeaseValid);
        }
        runContext.setWriteTicket(session.writeTicket);

        session.reactContext = runContext;

        AgentRunResult result = reActRuntime.run(runContext);

        //来源展示：只有回答实际引用并通过校验的证据才进入 sources。
        fillSources(result, runContext.getEvidenceRegistry());

        return result;
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
     * 填充回答来源。
     */
    private void fillSources(AgentRunResult result, EvidenceRegistry registry) {
        if (registry == null) {
            return;
        }

        for (KnowledgeEvidence evidence : registry.usedEvidences()) {
            AgentSourceVO source = new AgentSourceVO();
            source.setLabel(evidence.getCitationLabel());
            source.setEvidenceId(evidence.getEvidenceId());
            source.setDocId(evidence.getDocId());
            source.setTitle(evidence.getTitle());
            source.setScopeName(evidence.getKnowledgeScopeName());
            source.setVenueName(evidence.getVenueName());
            source.setCourtName(evidence.getCourtName());
            source.setPreview(preview(evidence.getText(), 120));
            source.setScore(evidence.getScore());
            source.setContentHash(evidence.getContentHash() == null
                    ? null
                    : evidence.getContentHash().substring(0,
                    Math.min(16, evidence.getContentHash().length())));

            result.getSources().add(source);
        }
    }

    /**
     * 结束本轮:组装结构化回复、提交会话快照、写最终 Trace。
     */
    private AgentRunResult finish(AgentRunResult result,
                                  AgentHarnessRequest request,
                                  String sessionId,
                                  RunSession session) {
        if (result == null) {
            result = AgentRunResult.failed("本次没有生成有效回复，请稍后再试。", "EMPTY_RESULT");
        }

        if (!StringUtils.hasText(result.getReplyText()) && StringUtils.hasText(result.getDisplayPayload())) {
            result.setReplyText("已生成待确认草稿，请确认或取消。");
        }

        if (result.getNewMessages() == null || result.getNewMessages().isEmpty()) {
            result.setNewMessages(buildSimpleMessages(request.getMessage(), result.getReplyText()));
        }

        //1. 组装卡片与动作：全部来自当前会话真实草稿，模型不自造。
        fillCardsAndActions(result, request.getUserId(), sessionId);

        //2. 计算最终历史与摘要。
        List<SessionMessage> finalHistory;
        String summary;
        int summaryVersion;

        if (session.reactContext != null) {
            finalHistory = session.reactContext.getMessages();
            summary = session.reactContext.getSummary();
            summaryVersion = session.reactContext.getSummaryVersion();
        } else {
            finalHistory = new ArrayList<>(session.snapshot == null
                    ? List.of()
                    : session.snapshot.getMessages());
            finalHistory.addAll(result.getNewMessages());
            summary = session.snapshot == null ? null : session.snapshot.getSummary();
            summaryVersion = session.snapshot == null ? 0 : session.snapshot.getSummaryVersion();
        }

        session.pendingHistory = new SessionHistory();
        session.pendingHistory.setMessages(finalHistory);
        session.pendingHistory.setSummary(summary);
        session.pendingHistory.setSummaryVersion(summaryVersion);

        //3. 构建本轮展示轮次并写入快照（按 runId 去重，供恢复展示）。
        upsertDisplayTurn(session.pendingHistory, request.getRunId(), request.getMessage(), result);

        //4. 摘要与裁剪后的消息一次提交；Redis 原子校验锁令牌，
        //锁失效（过期/被新请求接管）的旧请求不得覆盖新状态。
        boolean committed = commitSessionHistory(request.getUserId(), sessionId, session, result);
        result.setSessionCommitted(committed);

        if (!committed) {
            trace("SESSION_COMMIT", "会话消息提交失败", sessionId, null, "FAILED",
                    "session history store save failed", 0L);

            //文本回复时明确提示，避免用户以为多轮上下文已经保存。
            if (!StringUtils.hasText(result.getDisplayPayload())) {
                result.setReplyText(result.getReplyText()
                        + "\n\n（提示：本轮会话记录保存失败，多轮上下文可能不完整。）");
            }
        }

        trace("RESPONSE_ASSEMBLE", "结构化回复组装",
                "sessionId=" + sessionId,
                "status=" + result.getStatus()
                        + ", sources=" + result.getSources().size()
                        + ", cards=" + result.getCards().size()
                        + ", actions=" + result.getActions().size(),
                "SUCCESS", null, 0L);

        trace("FINAL_REPLY", "本轮结束: " + result.getStatus(),
                "sessionId=" + sessionId,
                result.getReplyText(),
                result.isBusinessSuccess() ? "SUCCESS" : "FAILED",
                result.getErrorCode(),
                result.getCostMs());

        //5. 持久运行终态；写入失败只降级，恢复查询会如实显示状态缺失。
        agentRunStore.finish(request.getUserId(), sessionId, request.getRunId(),
                result.getStatus(),
                com.sau.gym.admin.agent.run.AgentRunStage.FINISHING.name(),
                result.getErrorCode(),
                committed,
                session.pendingHistory == null ? -1L : session.pendingHistory.getRevision(),
                session.writeTicket);

        trace("RUN_FINISH", "运行终态已记录",
                "sessionId=" + sessionId + ", runId=" + request.getRunId(),
                "status=" + result.getStatus() + ", committed=" + committed,
                "SUCCESS", null, 0L);

        return result;
    }

    /**
     * 构建并合并本轮展示轮次。
     * turnId 使用该轮 runId，恢复合并与去重均使用稳定标识，不依赖下标或正文比较。
     */
    private void upsertDisplayTurn(SessionHistory history, String runId, String userMessage,
                                   AgentRunResult result) {
        if (history == null) {
            return;
        }

        if (history.getDisplayTurns() == null) {
            history.setDisplayTurns(new ArrayList<>());
        }

        String replyText = StringUtils.hasText(result.getReplyText())
                ? result.getReplyText()
                : result.getDisplayPayload();

        com.sau.gym.admin.agent.memory.model.DisplayTurn turn =
                com.sau.gym.admin.agent.memory.model.DisplayTurn.builder()
                        .turnId(runId)
                        .runId(runId)
                        .userText(userMessage)
                        .replyText(replyText)
                        .status(result.getStatus() == null ? null : result.getStatus().name())
                        .sources(result.getSources() == null
                                ? new ArrayList<>() : result.getSources())
                        .createTime(System.currentTimeMillis())
                        .build();

        //同 runId 重跑（理论不应发生）时替换，保证每轮只有一条展示记录。
        history.getDisplayTurns().removeIf(existing -> runId.equals(existing.getTurnId()));
        history.getDisplayTurns().add(turn);

        //有界：只保留最近 20 轮展示内容。
        while (history.getDisplayTurns().size() > 20) {
            history.getDisplayTurns().remove(0);
        }
    }

    /**
     * 组装卡片与动作。
     * 数据来源是当前会话真实草稿；没有草稿时不渲染任何按钮。
     */
    private void fillCardsAndActions(AgentRunResult result, Long userId, String sessionId) {
        PendingDraft draft;

        try {
            draft = userId == null ? null : draftStore.get(userId, sessionId);
        } catch (Exception e) {
            log.warn("[AgentHarness] 读取待确认草稿失败: {}", e.getMessage());
            return;
        }

        if (draft == null) {
            return;
        }

        //卡片与动作由共享组装器生成，与会话恢复快照保持一致。
        result.getCards().add(responseAssembler.buildCard(draft));
        result.getActions().addAll(responseAssembler.buildActions(draft));
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
     * 异常分支安静提交。
     * 同样受真实锁租约保护，失效时不覆盖。
     */
    private void commitQuietly(Long userId, String sessionId, AgentRunResult result, RunSession session) {
        try {
            if (session.lockHandle != null && !session.lockHandle.isLeaseValid()) {
                log.warn("[AgentHarness] 异常分支跳过会话提交：会话锁已失效, sessionId={}", sessionId);
                return;
            }

            if (session.lockHandle != null && !session.lockHandle.isLeaseValid()) {
                log.warn("[AgentHarness] 异常分支跳过会话提交：会话锁已失效, sessionId={}", sessionId);
                return;
            }

            session.pendingHistory = new SessionHistory();
            session.pendingHistory.setMessages(session.snapshot == null
                    ? result.getNewMessages()
                    : mergeHistory(session.snapshot.getMessages(), result.getNewMessages()));
            session.pendingHistory.setSummary(session.snapshot == null ? null : session.snapshot.getSummary());
            session.pendingHistory.setSummaryVersion(session.snapshot == null ? 0 : session.snapshot.getSummaryVersion());

            com.sau.gym.admin.agent.store.SessionWriteResult writeResult =
                    messageStore.saveHistoryIfHolder(userId, sessionId, session.pendingHistory, session.writeTicket);

            if (writeResult != com.sau.gym.admin.agent.store.SessionWriteResult.SAVED) {
                log.warn("[AgentHarness] 异常分支受保护提交未成功: {}, sessionId={}", writeResult, sessionId);
            }
        } catch (Exception e) {
            log.warn("[AgentHarness] 异常分支会话提交失败: {}", e.getMessage());
        }
    }

    /**
     * 合并历史与新增消息。
     */
    private List<SessionMessage> mergeHistory(List<SessionMessage> base, List<SessionMessage> produced) {
        List<SessionMessage> merged = new ArrayList<>(base == null ? List.of() : base);
        if (produced != null) {
            merged.addAll(produced);
        }
        return merged;
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
    private void refreshContext(Long userId, String sessionId, com.sau.gym.admin.agent.store.SessionWriteTicket ticket) {
        try {
            contextEnhanceService.refreshAfterAgent(userId, sessionId, ticket);
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
     * 读取当前待确认草稿类型名，供 SOP 场景判断使用。
     */
    private String resolveDraftTypeName(Long userId, String sessionId) {
        try {
            PendingDraft draft = userId == null ? null : draftStore.get(userId, sessionId);
            return draft == null ? null : draft.type().name();
        } catch (Exception e) {
            return null;
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
     * 文本预览。
     */
    private String preview(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        String value = text.replaceAll("\\s+", " ").trim();

        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    /**
     * 会话锁有效期兜底。
     */
    private long resolveLockTtl() {
        long ttl = properties.getSessionLockTtlMs();
        return ttl > 0 ? ttl : 60000L;
    }
}

package com.sau.gym.admin.agent.context;

import com.sau.gym.admin.agent.config.AgentHarnessProperties;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.runtime.AgentRunContext;
import com.sau.gym.admin.agent.service.AgentTraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 作者:hfj
 * 功能:上下文预算控制
 * 说明:
 * 1. 使用 TokenEstimator 估算，明确是估算而非服务端精确计数。
 * 2. 达到摘要触发阈值时，先对较早的完整轮次做模型摘要（计入辅助预算）；
 *    摘要成功后一次性提交“新摘要 + 裁剪后的历史”，绝不先删历史再生成摘要。
 * 3. 摘要失败时降级为完整轮次裁剪，保留当前输入与关键状态。
 * 4. 不会拆散 tool_calls 与 tool 结果的配对，也不会从中间截断 JSON。
 */
@Component
public class AgentContextBudgeter {

    private static final Logger log = LoggerFactory.getLogger(AgentContextBudgeter.class);

    private final AgentSessionSummarizer summarizer;

    private final AgentTraceService traceService;

    private final AgentHarnessProperties properties;

    public AgentContextBudgeter(AgentSessionSummarizer summarizer,
                                AgentTraceService traceService,
                                AgentHarnessProperties properties) {
        this.summarizer = summarizer;
        this.traceService = traceService;
        this.properties = properties;
    }

    /**
     * 在每次模型调用前控制上下文预算。
     *
     * @param runContext  运行上下文
     * @param fixedTokens 系统提示 + 摘要 + 工具 Schema 的固定开销估算
     * @return 当前估算 Token 数
     */
    public int enforce(AgentRunContext runContext, int fixedTokens) {
        List<SessionMessage> messages = runContext.getMessages();

        if (messages == null || messages.isEmpty()) {
            return fixedTokens;
        }

        int budget = runContext.getMaxContextTokens() > 0
                ? runContext.getMaxContextTokens()
                : Integer.MAX_VALUE;

        double triggerRatio = properties.getSummaryTriggerRatio() > 0
                ? properties.getSummaryTriggerRatio()
                : 0.75D;

        int triggerTokens = (int) Math.max(budget * triggerRatio, 1);

        int before = fixedTokens + estimate(messages);

        //低于触发阈值时直接返回，不在每次模型请求前无条件调用摘要模型。
        if (before <= triggerTokens) {
            return before;
        }

        //1. 对较早的完整轮次尝试摘要。
        List<SessionMessage> turnsToSummarize = collectSummarizableTurns(messages);

        if (!turnsToSummarize.isEmpty()) {
            AgentSessionSummarizer.SummarizeResult outcome = summarizer.summarize(
                    runContext,
                    runContext.getSummary(),
                    turnsToSummarize
            );

            if (outcome.ok) {
                //先拿到摘要再移除消息，不存在“先删历史再摘要”。
                messages.removeAll(turnsToSummarize);
                runContext.setSummary(outcome.summary);
                runContext.setSummaryVersion(runContext.getSummaryVersion() + 1);

                int after = fixedTokens + estimate(messages);

                traceCompact(before, after, turnsToSummarize.size(),
                        runContext.getSummaryVersion(), true, null);

                return after;
            }

            traceCompact(before, before, 0, runContext.getSummaryVersion(), false, outcome.failureReason);
        }

        //2. 降级：完整轮次裁剪，保留当前输入与关键状态。
        int total = before;

        while (total > budget) {
            int removed = dropOldestTurn(messages);

            if (removed <= 0) {
                log.warn("[AgentContext] 上下文无法继续裁剪, estimated={}, budget={}", total, budget);
                break;
            }

            total = fixedTokens + estimate(messages);
        }

        if (total != before) {
            traceCompact(before, total, Math.max(before - total, 0),
                    runContext.getSummaryVersion(), false, "摘要不可用，已降级为完整轮次裁剪");
        }

        return total;
    }

    /**
     * 估算消息列表的 Token 数。
     */
    public int estimate(List<SessionMessage> messages) {
        int total = 0;

        for (SessionMessage message : messages) {
            if (message == null) {
                continue;
            }

            total += 4;
            total += TokenEstimator.estimate(message.getRole());
            total += TokenEstimator.estimate(message.getContent());

            if (message.getToolCalls() != null) {
                for (var call : message.getToolCalls()) {
                    if (call == null) {
                        continue;
                    }

                    total += TokenEstimator.estimate(call.getId());
                    total += TokenEstimator.estimate(call.functionName());
                    total += TokenEstimator.estimate(call.argumentsJson());
                }
            }
        }

        return total;
    }

    /**
     * 划分轮次分组。
     * 每组从一条 user 消息开始到下一条 user 消息之前；
     * 开头没有 user 消息的孤儿前缀也算一组，保证 tool_calls 与 tool 结果整组处理。
     */
    private List<int[]> turnGroups(List<SessionMessage> messages) {
        List<Integer> starts = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).getRole())) {
                starts.add(i);
            }
        }

        List<int[]> groups = new ArrayList<>();

        if (starts.isEmpty()) {
            if (!messages.isEmpty()) {
                groups.add(new int[]{0, messages.size()});
            }
            return groups;
        }

        if (starts.get(0) > 0) {
            groups.add(new int[]{0, starts.get(0)});
        }

        for (int s = 0; s < starts.size(); s++) {
            int end = (s + 1 < starts.size()) ? starts.get(s + 1) : messages.size();
            groups.add(new int[]{starts.get(s), end});
        }

        return groups;
    }

    /**
     * 收集可摘要的较早完整轮次。
     * 保留最近 N 组（含当前输入所在的最后一组），更早的组作为摘要候选。
     */
    private List<SessionMessage> collectSummarizableTurns(List<SessionMessage> messages) {
        int keep = properties.getSummaryKeepRecentTurns() > 0
                ? properties.getSummaryKeepRecentTurns()
                : 4;

        List<int[]> groups = turnGroups(messages);

        if (groups.size() <= keep) {
            return List.of();
        }

        List<SessionMessage> result = new ArrayList<>();

        for (int g = 0; g < groups.size() - keep; g++) {
            int[] range = groups.get(g);
            result.addAll(messages.subList(range[0], range[1]));
        }

        return result;
    }

    /**
     * 降级路径：删除最早的一轮历史。
     *
     * @return 删除的消息条数，0 表示没有可安全删除的历史
     */
    private int dropOldestTurn(List<SessionMessage> messages) {
        List<int[]> groups = turnGroups(messages);

        if (groups.size() < 2) {
            return 0;
        }

        int start = groups.get(0)[0];
        int end = groups.get(1)[0];

        int removed = end - start;
        messages.subList(start, end).clear();

        return removed;
    }

    /**
     * 写 CONTEXT_COMPACT Trace。
     */
    private void traceCompact(int before,
                              int after,
                              int removedTurnMessages,
                              int summaryVersion,
                              boolean summarized,
                              String failureReason) {
        try {
            traceService.addStep(
                    "CONTEXT_COMPACT",
                    summarized ? "历史摘要成功" : "历史摘要降级裁剪",
                    "beforeEstimateTokens=" + before,
                    "afterEstimateTokens=" + after
                            + ", removedMessages=" + removedTurnMessages
                            + ", summaryVersion=" + summaryVersion
                            + (failureReason == null ? "" : ", reason=" + failureReason),
                    "SUCCESS",
                    failureReason,
                    0L
            );
        } catch (Exception e) {
            log.warn("[AgentContext] Trace 写入失败: {}", e.getMessage());
        }
    }
}

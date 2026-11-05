package com.sau.gym.admin.agent.context;

import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.runtime.AgentRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 作者:hfj
 * 功能:上下文预算控制
 * 说明:
 * 1. 使用 TokenEstimator 估算，不是服务端精确计数。
 * 2. 超出预算时按完整轮次删除最早历史，保留系统提示、当前用户输入和待办关键状态。
 * 3. 不会从中间截断 JSON，也不会拆散 tool_calls 与 tool 结果的配对。
 */
@Component
public class AgentContextBudgeter {

    private static final Logger log = LoggerFactory.getLogger(AgentContextBudgeter.class);

    /**
     * 在每次模型调用前压缩上下文。
     *
     * @param runContext       运行上下文
     * @param toolSchemaTokens 工具 Schema 的估算 Token 数
     * @return 实际估算 Token 数
     */
    public int enforce(AgentRunContext runContext, int toolSchemaTokens) {
        List<SessionMessage> messages = runContext.getMessages();

        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int budget = runContext.getMaxContextTokens() > 0
                ? runContext.getMaxContextTokens()
                : Integer.MAX_VALUE;

        int estimated = estimate(messages) + Math.max(toolSchemaTokens, 0);

        if (estimated <= budget) {
            return estimated;
        }

        //从最早的历史轮次开始删除，直到满足预算或只剩系统消息 + 当前输入。
        while (estimated > budget) {
            int removed = dropOldestTurn(messages);

            if (removed <= 0) {
                //无法继续安全裁剪时停止，由运行时决定是否继续调用模型。
                log.warn("[AgentContext] 上下文无法继续裁剪, estimated={}, budget={}", estimated, budget);
                break;
            }

            estimated = estimate(messages) + Math.max(toolSchemaTokens, 0);
        }

        return estimated;
    }

    /**
     * 删除最早的一轮历史。
     * 一轮历史从一条 user 消息开始，到下一个 user 消息之前结束，
     * 因此 assistant 的 tool_calls 与对应 tool 结果会一起被删除。
     *
     * @return 实际删除的消息条数，0 表示没有可安全删除的历史
     */
    private int dropOldestTurn(List<SessionMessage> messages) {
        if (messages.size() <= 2) {
            return 0;
        }

        //索引 0 固定是系统提示，必须保留。
        int lastUserIndex = -1;

        for (int i = messages.size() - 1; i >= 1; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                lastUserIndex = i;
                break;
            }
        }

        if (lastUserIndex <= 1) {
            //当前输入之前没有可删除的历史。
            return 0;
        }

        //最早的一轮历史从第一条历史 user 消息开始。
        int turnStart = -1;

        for (int i = 1; i < lastUserIndex; i++) {
            if ("user".equals(messages.get(i).getRole())) {
                turnStart = i;
                break;
            }
        }

        if (turnStart < 1 || turnStart == lastUserIndex) {
            //只剩当前这一轮，没有可安全删除的历史。
            return 0;
        }

        //结束边界是下一条 user 消息，保证 tool_calls 与 tool 结果整轮删除。
        int turnEnd = lastUserIndex;

        for (int i = turnStart + 1; i < lastUserIndex; i++) {
            if ("user".equals(messages.get(i).getRole())) {
                turnEnd = i;
                break;
            }
        }

        int removed = turnEnd - turnStart;
        messages.subList(turnStart, turnEnd).clear();

        return removed;
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
}

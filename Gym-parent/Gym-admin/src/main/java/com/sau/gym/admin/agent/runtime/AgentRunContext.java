package com.sau.gym.admin.agent.runtime;

import com.sau.gym.admin.agent.config.AgentHarnessProperties;
import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作者:hfj
 * 功能:单轮运行上下文
 * 说明:
 * 集中保存可信身份、会话标识、预算、已授权工具和本轮消息，
 * 让运行时、模型链和工具链共享同一份状态，避免依赖 ThreadLocal。
 */
@Data
public class AgentRunContext {

    /**
     * 可信用户ID，未登录时为空。
     */
    private Long userId;

    /**
     * 会话标识，跨轮稳定。
     */
    private String sessionId;

    /**
     * 本轮运行标识，与 traceId 一致。
     */
    private String runId;

    /**
     * 用户原始问题。
     */
    private String originalQuestion;

    /**
     * 当前业务上下文。
     */
    private AgentBusinessContext businessContext;

    /**
     * 本轮授权工具集合。
     */
    private Set<String> allowedToolCodes = new LinkedHashSet<>();

    /**
     * 本轮消息，包含 system、历史、当前用户输入、assistant 与 tool。
     */
    private List<SessionMessage> messages = new ArrayList<>();

    /**
     * 截止时间，使用单调时钟纳秒，避免系统时间调整影响预算。
     */
    private long deadlineNanos;

    /**
     * 最大模型请求次数。
     */
    private int maxModelCalls;

    /**
     * 最大工具派发次数。
     */
    private int maxToolCalls;

    /**
     * 上下文 Token 估算上限。
     */
    private int maxContextTokens;

    /**
     * 单条工具结果最大字符数。
     */
    private int maxToolResultChars;

    /**
     * 相同调用重复阈值。
     */
    private int repeatToolThreshold;

    /**
     * 已发起的模型请求次数。
     */
    private int modelCallCount;

    /**
     * 已派发的工具调用次数。
     */
    private int toolCallCount;

    /**
     * 工具调用签名 -> 次数。
     */
    private Map<String, Integer> toolSignatureCounts = new HashMap<>();

    /**
     * 会话锁是否仍然有效。
     * 锁丢失后运行时必须停止后续步骤。
     */
    private volatile boolean lockAlive = true;

    /**
     * 根据配置创建运行上下文。
     */
    public static AgentRunContext create(AgentHarnessProperties properties,
                                         Long userId,
                                         String sessionId,
                                         String runId,
                                         String originalQuestion,
                                         AgentBusinessContext businessContext,
                                         Set<String> allowedToolCodes) {
        AgentRunContext context = new AgentRunContext();

        context.setUserId(userId);
        context.setSessionId(sessionId);
        context.setRunId(runId);
        context.setOriginalQuestion(originalQuestion);
        context.setBusinessContext(businessContext);
        context.setAllowedToolCodes(allowedToolCodes == null ? new LinkedHashSet<>() : allowedToolCodes);

        context.setMaxModelCalls(properties.getMaxAgentModelCalls());
        context.setMaxToolCalls(properties.getMaxToolCalls());
        context.setMaxContextTokens(properties.getMaxContextTokens());
        context.setMaxToolResultChars(properties.getMaxToolResultChars());
        context.setRepeatToolThreshold(properties.getRepeatToolThreshold());

        long durationMs = properties.getMaxRunDurationMs() > 0 ? properties.getMaxRunDurationMs() : 60000L;
        context.setDeadlineNanos(System.nanoTime() + durationMs * 1_000_000L);

        return context;
    }

    /**
     * 剩余预算毫秒数。
     */
    public long remainingTimeMs() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return Math.max(remainingNanos / 1_000_000L, 0L);
    }

    /**
     * 是否已超时。
     */
    public boolean isExpired() {
        return remainingTimeMs() <= 0L;
    }

    /**
     * 是否还有模型调用预算。
     */
    public boolean hasModelCallBudget() {
        return modelCallCount < maxModelCalls;
    }

    /**
     * 消耗一次模型调用预算。
     */
    public void consumeModelCall() {
        modelCallCount++;
    }

    /**
     * 是否还有工具调用预算。
     */
    public boolean hasToolCallBudget() {
        return toolCallCount < maxToolCalls;
    }

    /**
     * 消耗一次工具派发预算。
     */
    public void consumeToolCall() {
        toolCallCount++;
    }

    /**
     * 记录一次工具调用签名，返回累计次数。
     */
    public int recordToolSignature(String signature) {
        int count = toolSignatureCounts.getOrDefault(signature, 0) + 1;
        toolSignatureCounts.put(signature, count);
        return count;
    }

    /**
     * 追加一条消息。
     */
    public void addMessage(SessionMessage message) {
        if (message != null) {
            messages.add(message);
        }
    }

    /**
     * 追加多条消息。
     */
    public void addMessages(List<SessionMessage> newMessages) {
        if (newMessages != null) {
            messages.addAll(newMessages);
        }
    }

    /**
     * 判断本轮是否还能继续执行。
     * 锁丢失、超时、中断任一成立都必须终止。
     */
    public boolean canContinue() {
        return lockAlive && !isExpired() && !Thread.currentThread().isInterrupted();
    }
}

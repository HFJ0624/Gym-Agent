package com.sau.gym.admin.agent.runtime;

import com.sau.gym.admin.agent.config.AgentHarnessProperties;
import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.rag.retrieval.EvidenceRegistry;
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
     * 系统提示词。
     * 不参与会话历史持久化，仅在每次模型请求组装时使用。
     */
    private String systemPrompt;

    /**
     * 历史会话摘要。
     * 由上下文压缩机制生成，跨轮持久化在会话历史中；
     * 业务结构化状态（场馆/场地ID、日期时段、草稿与确认状态）仍以 Redis 权威字段为准。
     */
    private String summary;

    /**
     * 摘要版本号，每次摘要成功后递增，便于 Trace 与调试。
     */
    private int summaryVersion;

    /**
     * 本轮证据登记上下文。
     * 知识工具检索到的证据登记于此，并分配 K1、K2 引用标签。
     */
    private EvidenceRegistry evidenceRegistry;

    /**
     * 会话受保护写入凭证。
     * 由 Harness 从真实锁句柄生成，随工具上下文下发，
     * 草稿、业务槽位等受保护写入在 Redis 侧原子校验令牌。
     */
    private transient com.sau.gym.admin.agent.store.SessionWriteTicket writeTicket;

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
     * 最大模型请求次数（仅 AGENT 用途的主循环调用）。
     */
    private int maxModelCalls;

    /**
     * 最大辅助模型调用次数（摘要、回答修正等共享）。
     */
    private int maxAuxModelCalls;

    /**
     * 本轮模型调用总次数上限（主循环 + 辅助）。
     */
    private int maxTotalModelCalls;

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
     * 已发起的模型请求次数（AGENT 用途）。
     */
    private int modelCallCount;

    /**
     * 已发起的辅助模型调用次数（摘要、修正等）。
     */
    private int auxModelCallCount;

    /**
     * 已派发的工具调用次数。
     */
    private int toolCallCount;

    /**
     * 工具调用签名 -> 次数。
     */
    private Map<String, Integer> toolSignatureCounts = new HashMap<>();

    /**
     * 会话锁有效性提供器。
     * 由 Harness 接线到真实 LockHandle 的租约状态（isLeaseValid），
     * 锁可能在模型请求期间丢失，因此不能在创建上下文时复制一次布尔值。
     * 未接线时（例如纯单元测试）默认视为有效。
     */
    private transient java.util.function.BooleanSupplier lockValidSupplier;

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
        context.setMaxAuxModelCalls(properties.getMaxAuxModelCalls());
        context.setMaxTotalModelCalls(properties.getMaxTotalModelCalls());
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
     * 是否还有模型调用预算（AGENT 用途）。
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
     * 是否还有辅助模型调用预算（摘要、修正等共享）。
     */
    public boolean hasAuxModelCallBudget() {
        return maxAuxModelCalls <= 0 || auxModelCallCount < maxAuxModelCalls;
    }

    /**
     * 消耗一次辅助模型调用预算。
     */
    public void consumeAuxModelCall() {
        auxModelCallCount++;
    }

    /**
     * 是否还有本轮模型调用总预算。
     */
    public boolean hasTotalModelCallBudget() {
        return maxTotalModelCalls <= 0 || getTotalModelCallCount() < maxTotalModelCalls;
    }

    /**
     * 本轮模型调用总次数。
     */
    public int getTotalModelCallCount() {
        return modelCallCount + auxModelCallCount;
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
     * 锁失效（真实租约状态）、超时、中断任一成立都必须终止。
     */
    public boolean canContinue() {
        return isLockValid() && !isExpired() && !Thread.currentThread().isInterrupted();
    }

    /**
     * 真实会话锁租约状态。
     */
    public boolean isLockValid() {
        return lockValidSupplier == null || lockValidSupplier.getAsBoolean();
    }
}

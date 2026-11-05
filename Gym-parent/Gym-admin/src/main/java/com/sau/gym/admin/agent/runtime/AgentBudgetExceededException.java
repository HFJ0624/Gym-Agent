package com.sau.gym.admin.agent.runtime;

/**
 * 作者:hfj
 * 功能:运行预算耗尽异常
 * 说明:
 * 只在“无法继续下一步”时抛出，由运行时统一转换成 BUDGET_EXCEEDED 终态。
 * 工具层面的预算不足不走异常，而是返回结构化工具结果，保证消息配对完整。
 */
public class AgentBudgetExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 终止原因码，例如 MODEL_CALL_LIMIT / TIMEOUT / LOCK_LOST。
     */
    private final String reasonCode;

    public AgentBudgetExceededException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}

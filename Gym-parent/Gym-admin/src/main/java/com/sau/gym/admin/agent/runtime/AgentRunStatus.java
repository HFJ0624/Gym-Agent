package com.sau.gym.admin.agent.runtime;

/**
 * 作者:hfj
 * 功能:一次 Agent 运行的终态
 * 说明:
 * 前端不感知异常堆栈，只感知这些明确的业务终态。
 */
public enum AgentRunStatus {

    /**
     * 运行中（尚未产生终态）。
     */
    RUNNING,

    /**
     * 正常得到最终回答。
     */
    COMPLETED,

    /**
     * 需要向用户追问缺失信息。
     */
    NEED_CLARIFICATION,

    /**
     * 已生成草稿，等待用户确认。
     */
    WAITING_CONFIRMATION,

    /**
     * 运行失败，例如模型异常、解析失败或工具链路异常。
     */
    FAILED,

    /**
     * 轮数、工具次数、上下文或耗时预算耗尽。
     */
    BUDGET_EXCEEDED,

    /**
     * 运行被中断：进程在产生终态前退出，且原持有者租约已失效。
     * 由运行存储在查询时按条件写识别，不代表业务结果，结果需重新核实。
     */
    INTERRUPTED
}

package com.sau.gym.admin.agent.run;

/**
 * 作者:hfj
 * 功能:运行阶段标记
 * 说明:
 * 只基于后端执行事实更新，用于区分“安全查询中断”与
 * “已进入确认/业务提交阶段而结果需要核实”。
 * 不由模型推断，也不代表业务成功。
 */
public enum AgentRunStage {

    /**
     * 咨询/工具执行阶段，尚未进入任何办理动作。
     */
    CONSULTING,

    /**
     * 已生成待确认草稿。
     */
    WAITING_CONFIRMATION,

    /**
     * 正在执行确认办理（可能触及真实业务提交）。
     */
    CONFIRMING,

    /**
     * 收尾阶段（会话提交、Trace 收尾）。
     */
    FINISHING
}

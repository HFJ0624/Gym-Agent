package com.sau.gym.admin.agent.workflow;

/**
 * 作者:hfj
 * 功能:本轮流程类型
 * 说明:
 * 用轻量路由把确认、取消、追问、直达查询和 ReAct 咨询区分开，
 * 避免所有请求都进入模型自由选择工具的路径。
 */
public enum WorkflowType {

    /**
     * 确认预约、确认下单、确认取消、取消当前草稿。
     */
    CONFIRMATION,

    /**
     * 用户指定预约ID，直接生成取消预约草稿。
     */
    CANCEL_BOOKING_DRAFT,

    /**
     * 信息不足，直接追问。
     */
    CLARIFY,

    /**
     * 明确的当前场地预约，走直达工具。
     * 直达失败时回落到 REACT。
     */
    DIRECT_ROUTE,

    /**
     * 规则咨询、组合问题、闲聊等，走 ReAct 循环。
     */
    REACT
}

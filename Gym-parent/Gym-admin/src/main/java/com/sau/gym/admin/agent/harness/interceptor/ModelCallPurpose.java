package com.sau.gym.admin.agent.harness.interceptor;

/**
 * 作者:hfj
 * 功能:模型调用用途分类
 * 说明:
 * 新增的生成调用必须声明用途，不允许存在绕过预算统计的隐藏模型调用。
 */
public enum ModelCallPurpose {

    /**
     * 普通客服与 ReAct 主循环。
     */
    AGENT,

    /**
     * 知识问答的最终答案生成（独立 RAG 问答页等）。
     */
    KNOWLEDGE_ANSWER,

    /**
     * 会话历史自动摘要。
     */
    CONTEXT_SUMMARY,

    /**
     * 引用/格式等回答修正。
     */
    ANSWER_REPAIR
}

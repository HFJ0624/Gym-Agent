package com.sau.gym.admin.agent.llm;

/**
 * 作者:hfj
 * 功能:模型客户端接口
 * 说明:
 * 只负责一次 Chat Completions 协议交互，不承担任何业务流程、
 * 不负责工具执行、不依赖 AiServices 或框架 Agent 机制。
 */
public interface LlmClient {

    /**
     * 发起一次非流式模型调用。
     *
     * @param request 已完成组装的模型请求
     * @return 模型响应
     * @throws LlmException 配置错误、网络错误、超时、非成功状态或解析失败
     */
    LlmChatResponse chat(LlmChatRequest request);
}

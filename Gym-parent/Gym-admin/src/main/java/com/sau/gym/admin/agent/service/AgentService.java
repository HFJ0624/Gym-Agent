package com.sau.gym.admin.agent.service;

import com.sau.gym.admin.agent.runtime.AgentRunResult;
import com.sau.gym.model.dto.agent.AgentChatDto;

public interface AgentService {

    /**
     * 执行一轮前台客服对话。
     * 返回结构化运行结果；旧的纯文本接口与新的 v2 结构化接口
     * 都基于同一个结果适配，保证一次请求只运行一次 Harness。
     *
     * @param userId       可信用户ID，未登录可为空
     * @param agentChatDto 请求参数
     * @return 运行结果
     */
    AgentRunResult chat(Long userId, AgentChatDto agentChatDto);
}

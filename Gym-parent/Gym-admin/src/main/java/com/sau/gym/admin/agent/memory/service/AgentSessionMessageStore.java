package com.sau.gym.admin.agent.memory.service;

import com.sau.gym.admin.agent.memory.model.SessionMessage;

import java.util.List;

/**
 * 作者:hfj
 * 功能:会话协议消息存储接口
 * 说明:
 * 自建 Harness 只使用这一套权威会话历史，
 * 不再依赖 MessageWindowChatMemory，也不再按 userId 重复拼接记忆提示词。
 */
public interface AgentSessionMessageStore {

    /**
     * 读取会话历史。
     *
     * @param userId    用户ID，未登录时可为空
     * @param sessionId 会话ID
     * @return 历史消息，读取失败时返回空列表
     */
    List<SessionMessage> load(Long userId, String sessionId);

    /**
     * 追加本轮消息。
     * 本轮结束时一次性提交完整协议消息，避免逐条写入造成半截状态。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param messages  本轮新增消息
     * @return 是否写入成功
     */
    boolean append(Long userId, String sessionId, List<SessionMessage> messages);

    /**
     * 清空会话历史。
     */
    void clear(Long userId, String sessionId);
}

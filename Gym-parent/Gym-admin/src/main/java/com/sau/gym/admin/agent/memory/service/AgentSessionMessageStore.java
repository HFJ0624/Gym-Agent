package com.sau.gym.admin.agent.memory.service;

import com.sau.gym.admin.agent.memory.model.SessionHistory;

/**
 * 作者:hfj
 * 功能:会话协议消息存储接口
 * 说明:
 * 自建 Harness 只使用这一套权威会话历史，
 * 不再依赖 MessageWindowChatMemory，也不再按 userId 重复拼接记忆提示词。
 * 第二轮起以快照形式存储：裁剪后的消息 + 历史摘要一次提交，
 * 避免“先删历史再生成摘要”造成的信息丢失。
 */
public interface AgentSessionMessageStore {

    /**
     * 读取会话历史快照。
     * 兼容第一轮的纯消息数组格式：旧数据 summary 为空。
     *
     * @param userId    用户ID，未登录时可为空
     * @param sessionId 会话ID
     * @return 会话历史快照；读取失败时返回空快照
     */
    SessionHistory loadHistory(Long userId, String sessionId);

    /**
     * 保存会话历史快照。
     * 摘要与裁剪后的消息在同一次写入中提交。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param history   会话历史快照
     * @return 是否写入成功
     */
    boolean saveHistory(Long userId, String sessionId, SessionHistory history);

    /**
     * 清空会话历史。
     */
    void clear(Long userId, String sessionId);
}

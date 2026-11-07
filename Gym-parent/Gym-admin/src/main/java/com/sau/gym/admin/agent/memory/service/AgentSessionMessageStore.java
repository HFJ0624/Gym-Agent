package com.sau.gym.admin.agent.memory.service;

import com.sau.gym.admin.agent.memory.model.SessionHistory;
import com.sau.gym.admin.agent.store.SessionWriteResult;
import com.sau.gym.admin.agent.store.SessionWriteTicket;

/**
 * 作者:hfj
 * 功能:会话协议消息存储接口
 * 说明:
 * 自建 Harness 只使用这一套权威会话历史，
 * 不再依赖 MessageWindowChatMemory，也不再按 userId 重复拼接记忆提示词。
 * 第三轮起支持持有者保护写入：Redis 原子校验锁令牌后再提交快照，
 * 关闭“检查后写入”之间的失锁覆盖窗口。
 */
public interface AgentSessionMessageStore {

    /**
     * 读取会话历史快照。
     * 兼容第一轮的纯消息数组格式：旧数据 summary 为空。
     * 读取异常时快照 loadFailed=true，调用方不得用空历史覆盖原记录。
     *
     * @param userId    用户ID，未登录时可为空
     * @param sessionId 会话ID
     * @return 会话历史快照；读取失败时 loadFailed=true
     */
    SessionHistory loadHistory(Long userId, String sessionId);

    /**
     * 保存会话历史快照（无持有者保护）。
     * 仅兼容保留，Agent 主链必须使用 saveHistoryIfHolder。
     *
     * @param userId   用户ID
     * @param sessionId 会话ID
     * @param history  会话历史快照
     * @return 是否写入成功
     */
    boolean saveHistory(Long userId, String sessionId, SessionHistory history);

    /**
     * 持有者保护的会话快照写入。
     * Redis 原子校验锁令牌后写入，锁丢失返回 LOCK_LOST，Redis 异常返回 STORE_UNAVAILABLE。
     *
     * @param userId   用户ID
     * @param sessionId 会话ID
     * @param history  会话历史快照
     * @param ticket   真实锁句柄生成的写入凭证
     * @return 写入结果
     */
    SessionWriteResult saveHistoryIfHolder(Long userId, String sessionId, SessionHistory history,
                                           SessionWriteTicket ticket);

    /**
     * 清空会话历史。
     */
    void clear(Long userId, String sessionId);
}

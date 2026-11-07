package com.sau.gym.admin.agent.store;

/**
 * 作者:hfj
 * 功能:会话受保护写入结果
 * 说明:
 * 必须可区分“已保存”“锁已不属于自己”“存储暂时不可用”，
 * 上层据此决定是否宣称本轮已可靠保存，不能用一个布尔值掩盖冲突。
 */
public enum SessionWriteResult {

    /**
     * 原子校验通过并已写入。
     */
    SAVED,

    /**
     * 锁已丢失或被新持有者接管，写入被拒绝。
     */
    LOCK_LOST,

    /**
     * Redis 操作异常，写入结果不明。
     * 上层不得宣称已保存，也不得自动重试真实业务写操作。
     */
    STORE_UNAVAILABLE
}

package com.sau.gym.admin.agent.store;

/**
 * 作者:hfj
 * 功能:会话受保护写入凭证
 * 说明:
 * 由真实 LockHandle 生成，携带锁 key 与持有者令牌。
 * 令牌只来自服务端执行上下文，绝不来自前端、模型参数或摘要。
 * Redis 侧在原子脚本内校验令牌后再写入，关闭“检查后写入”之间的失锁覆盖窗口。
 */
public record SessionWriteTicket(String lockKey, String token) {

    public static SessionWriteTicket of(String lockKey, String token) {
        return new SessionWriteTicket(lockKey, token);
    }
}

package com.sau.gym.admin.agent.store;

import com.sau.gym.admin.agent.util.AgentSessionIdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 作者:hfj
 * 功能:草稿缓存器
 * 作用:
 * 使用 Redis 保存草稿，并设置过期时间。
 * 草稿按 userId + sessionId 维度隔离，避免不同会话互相覆盖。
 * 日期: 2026/4/23 14:44
 */
@Component
public class AgentDraftStore {

    /**
     * 新 key 前缀: agent:draft:user:{userId}:session:{sessionId}
     */
    private static final String KEY_PREFIX = "agent:draft:user:";

    /**
     * 旧版 key 只允许 default 会话读取和迁移，避免旧数据复制到每个新会话。
     */
    private final RedisTemplate<String, PendingDraft> redisTemplate;

    /**
     * 受保护写入：Redis 侧原子校验锁令牌后再写入/删除。
     */
    private final SessionProtectedRedis protectedRedis;

    /**
     * 草稿过期时间，单位：分钟。
     * 默认 15 分钟。
     */
    @Value("${gym.agent.draft.ttl-minutes:15}")
    private Long ttlMinutes;

    public AgentDraftStore(RedisTemplate<String, PendingDraft> agentDraftRedisTemplate,
                           SessionProtectedRedis protectedRedis) {
        this.redisTemplate = agentDraftRedisTemplate;
        this.protectedRedis = protectedRedis;
    }

    /**
     * 保存草稿。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param draft     草稿对象
     */
    public void save(Long userId, String sessionId, PendingDraft draft) {
        if (userId == null || draft == null) {
            return;
        }

        String realSessionId = AgentSessionIdUtil.normalize(sessionId);

        redisTemplate.opsForValue().set(
                buildKey(userId, realSessionId),
                draft,
                Duration.ofMinutes(resolveTtlMinutes())
        );

        //default 会话写入后清理旧 key，避免旧草稿被再次读出来。
        if (AgentSessionIdUtil.isDefault(realSessionId)) {
            redisTemplate.delete(buildLegacyKey(userId));
        }
    }

    /**
     * 持有者保护的草稿保存。
     * Redis 原子校验锁令牌后写入，旧请求在失锁后无法覆盖新持有者的草稿。
     * 值序列化复用模板自身的 valueSerializer（JDK 字节），与普通读写完全兼容。
     *
     * @return 写入结果；非 SAVED 时调用方必须如实告知草稿未保存
     */
    public SessionWriteResult saveIfHolder(Long userId, String sessionId, PendingDraft draft,
                                           SessionWriteTicket ticket) {
        if (userId == null || draft == null) {
            return SessionWriteResult.SAVED;
        }

        if (ticket == null) {
            //Agent 主链必须携带令牌；无令牌的调用走 legacy save 且不属于受保护路径。
            return SessionWriteResult.LOCK_LOST;
        }

        @SuppressWarnings("unchecked")
        org.springframework.data.redis.serializer.RedisSerializer<Object> serializer =
                (org.springframework.data.redis.serializer.RedisSerializer<Object>) redisTemplate.getValueSerializer();

        byte[] rawValue = serializer.serialize(draft);

        return protectedRedis.writeIfHolder(
                redisTemplate,
                ticket.lockKey(),
                ticket.token(),
                buildKey(userId, AgentSessionIdUtil.normalize(sessionId)),
                rawValue,
                Duration.ofMinutes(resolveTtlMinutes())
        );
    }

    /**
     * 持有者保护的草稿删除。
     *
     * @return 写入结果
     */
    public SessionWriteResult clearIfHolder(Long userId, String sessionId, SessionWriteTicket ticket) {
        if (userId == null) {
            return SessionWriteResult.SAVED;
        }

        if (ticket == null) {
            return SessionWriteResult.LOCK_LOST;
        }

        return protectedRedis.deleteIfHolder(
                redisTemplate,
                ticket.lockKey(),
                ticket.token(),
                buildKey(userId, AgentSessionIdUtil.normalize(sessionId))
        );
    }

    /**
     * 获取草稿。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 草稿对象
     */
    public PendingDraft get(Long userId, String sessionId) {
        if (userId == null) {
            return null;
        }

        String realSessionId = AgentSessionIdUtil.normalize(sessionId);
        String key = buildKey(userId, realSessionId);

        PendingDraft draft = redisTemplate.opsForValue().get(key);

        if (draft != null) {
            return draft;
        }

        //只有 default 会话兼容旧 key，读取后立即迁移并删除旧 key。
        if (!AgentSessionIdUtil.isDefault(realSessionId)) {
            return null;
        }

        PendingDraft legacyDraft = redisTemplate.opsForValue().get(buildLegacyKey(userId));

        if (legacyDraft == null) {
            return null;
        }

        redisTemplate.opsForValue().set(
                key,
                legacyDraft,
                Duration.ofMinutes(resolveTtlMinutes())
        );
        redisTemplate.delete(buildLegacyKey(userId));

        return legacyDraft;
    }

    /**
     * 删除草稿。
     * 用户确认成功或取消操作后调用。
     */
    public void clear(Long userId, String sessionId) {
        if (userId == null) {
            return;
        }

        String realSessionId = AgentSessionIdUtil.normalize(sessionId);

        redisTemplate.delete(buildKey(userId, realSessionId));

        if (AgentSessionIdUtil.isDefault(realSessionId)) {
            redisTemplate.delete(buildLegacyKey(userId));
        }
    }

    /**
     * 判断当前会话是否有待确认草稿。
     */
    public boolean hasDraft(Long userId, String sessionId) {
        return get(userId, sessionId) != null;
    }

    /**
     * 获取草稿剩余过期时间，单位：秒。
     */
    public Long getExpireSeconds(Long userId, String sessionId) {
        if (userId == null) {
            return -1L;
        }

        String realSessionId = AgentSessionIdUtil.normalize(sessionId);
        Long expire = redisTemplate.getExpire(buildKey(userId, realSessionId));

        if (expire != null && expire > 0) {
            return expire;
        }

        if (AgentSessionIdUtil.isDefault(realSessionId)) {
            return redisTemplate.getExpire(buildLegacyKey(userId));
        }

        return expire;
    }

    /**
     * 构造新 key。
     */
    private String buildKey(Long userId, String sessionId) {
        return KEY_PREFIX + userId + ":session:" + sessionId;
    }

    /**
     * 构造旧版 userId 级 key。
     * 与新版前缀相同，只是不带 :session:{sessionId} 后缀。
     */
    private String buildLegacyKey(Long userId) {
        return KEY_PREFIX + userId;
    }

    /**
     * 草稿过期时间兜底，避免配置缺失导致 Duration 异常。
     */
    private long resolveTtlMinutes() {
        return ttlMinutes == null || ttlMinutes <= 0 ? 15L : ttlMinutes;
    }
}

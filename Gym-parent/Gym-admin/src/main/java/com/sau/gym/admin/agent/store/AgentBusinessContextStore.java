package com.sau.gym.admin.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.admin.agent.util.AgentSessionIdUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 作者:hfj
 * 功能:Agent 业务上下文 Redis 存储器
 * 说明:
 * 1. 上下文按 userId + sessionId 维度隔离，避免不同会话共用最近场馆、场地等槽位。
 * 2. default 会话兼容旧的 userId 级 key，读取后迁移并清理旧 key。
 * 3. 用户 6 小时不交互会自动过期。
 * 日期: 2026/5/6 20:09
 */
@Component
public class AgentBusinessContextStore {

    /**
     * Redis key 前缀: agent:business-context:user:{userId}:session:{sessionId}
     */
    private static final String KEY_PREFIX = "agent:business-context:user:";

    /**
     * 上下文有效期。
     * 时间不要太长，否则用户几天后再问“这个场馆”，可能引用到很早之前的场馆。
     */
    private static final Duration TTL = Duration.ofHours(6);

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    /**
     * 持有者保护写入（Redis 原子校验锁令牌）。
     */
    private final SessionProtectedRedis protectedRedis;

    public AgentBusinessContextStore(StringRedisTemplate stringRedisTemplate,
                                     ObjectMapper objectMapper,
                                     SessionProtectedRedis protectedRedis) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.protectedRedis = protectedRedis;
    }

    /**
     * 获取指定会话的业务上下文。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 上下文对象；不存在时返回 null
     */
    public AgentBusinessContext get(Long userId, String sessionId) {
        if (userId == null) {
            return null;
        }

        String realSessionId = AgentSessionIdUtil.normalize(sessionId);

        AgentBusinessContext context = read(buildKey(userId, realSessionId));

        if (context != null) {
            return context;
        }

        //只有 default 会话兼容旧 key，避免旧数据扩散到所有新会话。
        if (!AgentSessionIdUtil.isDefault(realSessionId)) {
            return null;
        }

        AgentBusinessContext legacyContext = read(buildLegacyKey(userId));

        if (legacyContext == null) {
            return null;
        }

        legacyContext.setSessionId(realSessionId);
        save(legacyContext);

        stringRedisTemplate.delete(buildLegacyKey(userId));

        return legacyContext;
    }

    /**
     * 保存业务上下文。
     *
     * @param context 上下文对象，必须带 userId 和 sessionId
     */
    public void save(AgentBusinessContext context) {
        if (context == null || context.getUserId() == null) {
            return;
        }

        String realSessionId = AgentSessionIdUtil.normalize(context.getSessionId());
        context.setSessionId(realSessionId);

        try {
            String json = objectMapper.writeValueAsString(context);
            stringRedisTemplate.opsForValue().set(
                    buildKey(context.getUserId(), realSessionId),
                    json,
                    TTL
            );

            if (AgentSessionIdUtil.isDefault(realSessionId)) {
                stringRedisTemplate.delete(buildLegacyKey(context.getUserId()));
            }
        } catch (Exception e) {
            //保存上下文失败不影响聊天主流程，但需要留下可观察日志。
            System.out.println("[AgentContext] 保存业务上下文失败：" + e.getMessage());
        }
    }

    /**
     * 持有者保护的业务上下文保存。
     * Redis 原子校验锁令牌后写入，旧请求在失锁后无法覆盖新持有者的槽位。
     *
     * @return 写入结果；非 SAVED 时调用方需知悉槽位未更新
     */
    public SessionWriteResult saveIfHolder(AgentBusinessContext context, SessionWriteTicket ticket) {
        if (context == null || context.getUserId() == null) {
            return SessionWriteResult.SAVED;
        }

        if (ticket == null) {
            //Agent 主链必须携带令牌。
            return SessionWriteResult.LOCK_LOST;
        }

        String realSessionId = AgentSessionIdUtil.normalize(context.getSessionId());
        context.setSessionId(realSessionId);

        try {
            String json = objectMapper.writeValueAsString(context);

            return protectedRedis.writeIfHolder(
                    ticket.lockKey(),
                    ticket.token(),
                    buildKey(context.getUserId(), realSessionId),
                    json,
                    TTL
            );
        } catch (Exception e) {
            System.out.println("[AgentContext] 受保护保存业务上下文失败：" + e.getMessage());
            return SessionWriteResult.STORE_UNAVAILABLE;
        }
    }

    /**
     * 清除指定会话的业务上下文。
     */
    public void clear(Long userId, String sessionId) {
        if (userId == null) {
            return;
        }

        String realSessionId = AgentSessionIdUtil.normalize(sessionId);

        stringRedisTemplate.delete(buildKey(userId, realSessionId));

        if (AgentSessionIdUtil.isDefault(realSessionId)) {
            stringRedisTemplate.delete(buildLegacyKey(userId));
        }
    }

    /**
     * 读取并反序列化。
     */
    private AgentBusinessContext read(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);

            if (!StringUtils.hasText(json)) {
                return null;
            }

            return objectMapper.readValue(json, AgentBusinessContext.class);
        } catch (Exception e) {
            //上下文读取失败按无上下文处理，不阻塞本轮对话。
            System.out.println("[AgentContext] 读取业务上下文失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 构建新 key。
     */
    private String buildKey(Long userId, String sessionId) {
        return KEY_PREFIX + userId + ":session:" + sessionId;
    }

    /**
     * 构建旧版 userId 级 key。
     */
    private String buildLegacyKey(Long userId) {
        return KEY_PREFIX + userId;
    }
}

package com.sau.gym.admin.agent.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 作者:hfj
 * 功能:会话状态受保护 Redis 写入
 * 说明:
 * 1. 所有需要持有者保护的会话状态写入（协议历史、草稿、业务槽位、运行记录）
 *    都通过这里的 Lua 脚本在一次原子操作内完成“校验持有令牌 → 写入”，
 *    不允许拆成 GET 锁、Java 比较、SET 数据三步。
 * 2. 序列化兼容是关键：
 *    - 锁由 StringRedisTemplate 写入，锁值是原始字符串；
 *    - 草稿模板的 value 是 JDK 序列化字节，协议历史/业务槽位/运行记录是 JSON 字符串。
 *    因此脚本参数统一使用 RAW_ARG_SERIALIZER（String/byte[]/Long 原样透传），
 *    数据值由各 Store 用自己模板的 valueSerializer 序列化成 byte[] 后传入，
 *    保证写回 Redis 的字节与普通读写完全一致。
 * 3. keys 使用模板自身的 keySerializer（均为 String）。
 * 4. 当前部署为单实例/哨兵 Redis；脚本涉及多 key 时要求同槽位，
 *    所有 key 共享同一前缀，集群部署前需确认 hash-slot 策略。
 */
@Component
public class SessionProtectedRedis {

    private static final Logger log = LoggerFactory.getLogger(SessionProtectedRedis.class);

    /**
     * Lua 脚本整数结果序列化。
     */
    private static final GenericToStringSerializer<Long> LONG_RESULT = new GenericToStringSerializer<>(Long.class);

    /**
     * 脚本参数序列化：字符串按 UTF-8，byte[] 原样，Long 转十进制字符串。
     */
    private static final RedisSerializer<Object> RAW_ARG_SERIALIZER = new RedisSerializer<>() {
        @Override
        public byte[] serialize(Object value) throws SerializationException {
            if (value == null) {
                return new byte[0];
            }

            if (value instanceof byte[] bytes) {
                return bytes;
            }

            if (value instanceof String string) {
                return string.getBytes(StandardCharsets.UTF_8);
            }

            if (value instanceof Long || value instanceof Integer) {
                return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            }

            throw new SerializationException("受保护脚本参数仅支持 String/byte[]/Long: " + value.getClass());
        }

        @Override
        public Object deserialize(byte[] bytes) throws SerializationException {
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    };

    /**
     * 校验锁令牌后写入数据。
     * KEYS[1]=锁key, KEYS[2]=数据key；ARGV[1]=令牌, ARGV[2]=数据值(原始字节), ARGV[3]=过期毫秒
     */
    private static final DefaultRedisScript<Long> WRITE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('set', KEYS[2], ARGV[2], 'PX', ARGV[3]) "
                    + "return 1 "
                    + "end "
                    + "return 0",
            Long.class
    );

    /**
     * 校验锁令牌后删除数据。
     * KEYS[1]=锁key, KEYS[2]=数据key；ARGV[1]=令牌
     * 令牌匹配时无论数据是否存在都返回 1；不匹配返回 0。
     */
    private static final DefaultRedisScript<Long> DELETE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('del', KEYS[2]) "
                    + "return 1 "
                    + "end "
                    + "return 0",
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    public SessionProtectedRedis(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 持有者保护写入（原始字节值，由调用方用目标模板的 valueSerializer 序列化）。
     *
     * @param template 目标数据所属模板（决定 key 序列化与连接库）
     * @param lockKey  会话锁 key（由真实 LockHandle 提供）
     * @param token    锁持有者令牌
     * @param dataKey  目标数据 key
     * @param rawValue 序列化后的原始字节（与该模板普通读写格式一致）
     * @param ttl      数据有效期
     */
    public SessionWriteResult writeIfHolder(RedisTemplate<String, ?> template,
                                            String lockKey,
                                            String token,
                                            String dataKey,
                                            byte[] rawValue,
                                            Duration ttl) {
        try {
            Long result = template.execute(
                    WRITE_SCRIPT,
                    RAW_ARG_SERIALIZER,
                    LONG_RESULT,
                    List.of(dataKey),
                    token,
                    rawValue,
                    ttl.toMillis()
            );

            if (result != null && result == 1L) {
                return SessionWriteResult.SAVED;
            }

            log.warn("[SessionProtected] 写入被拒绝：锁已不属于当前持有者, dataKey={}", dataKey);
            return SessionWriteResult.LOCK_LOST;

        } catch (Exception e) {
            log.warn("[SessionProtected] 受保护写入异常, dataKey={}, reason={}", dataKey, e.getMessage());
            return SessionWriteResult.STORE_UNAVAILABLE;
        }
    }

    /**
     * 持有者保护写入（字符串值便捷重载，用于 String 模板）。
     */
    public SessionWriteResult writeIfHolder(String lockKey,
                                            String token,
                                            String dataKey,
                                            String value,
                                            Duration ttl) {
        return writeIfHolder(
                stringRedisTemplate,
                lockKey,
                token,
                dataKey,
                value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8),
                ttl
        );
    }

    /**
     * 持有者保护删除。
     */
    public SessionWriteResult deleteIfHolder(RedisTemplate<String, ?> template,
                                             String lockKey,
                                             String token,
                                             String dataKey) {
        try {
            Long result = template.execute(
                    DELETE_SCRIPT,
                    RAW_ARG_SERIALIZER,
                    LONG_RESULT,
                    List.of(dataKey),
                    token
            );

            if (result != null && result == 1L) {
                return SessionWriteResult.SAVED;
            }

            log.warn("[SessionProtected] 删除被拒绝：锁已不属于当前持有者, dataKey={}", dataKey);
            return SessionWriteResult.LOCK_LOST;

        } catch (Exception e) {
            log.warn("[SessionProtected] 受保护删除异常, dataKey={}, reason={}", dataKey, e.getMessage());
            return SessionWriteResult.STORE_UNAVAILABLE;
        }
    }
}

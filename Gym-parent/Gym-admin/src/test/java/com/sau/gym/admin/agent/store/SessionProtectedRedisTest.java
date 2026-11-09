package com.sau.gym.admin.agent.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:受保护写入 Lua 调用约定测试（本轮回归）
 * 说明:
 * 脚本约定 KEYS[1]=锁 key、KEYS[2]=数据 key；
 * Java 侧必须按顺序传入两个 key，否则脚本读写错位。
 * 本测试锁定该约定，并覆盖 SAVED/LOCK_LOST/STORE_UNAVAILABLE 三种结果。
 */
class SessionProtectedRedisTest {

    private StringRedisTemplate stringRedisTemplate;

    private SessionProtectedRedis protectedRedis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        protectedRedis = new SessionProtectedRedis(stringRedisTemplate);
    }

    @Test
    void 写入必须按顺序传入锁key与数据key() {
        //与生产调用一致：execute(script, argsSerializer, resultSerializer, keys, args...)
        when(stringRedisTemplate.execute(any(RedisScript.class), any(), any(), anyList(), any(Object[].class)))
                .thenReturn(1L);

        SessionWriteResult result = protectedRedis.writeIfHolder(
                "agent:session:lock:user:1:session:s-a", "token",
                "agent:session:messages:1:s-a", "{\"messages\":[]}", Duration.ofHours(6));

        assertEquals(SessionWriteResult.SAVED, result);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), any(), any(),
                keys.capture(), any(Object[].class));

        //KEYS[1]=锁key，KEYS[2]=数据key，顺序不能颠倒。
        assertEquals(List.of(
                "agent:session:lock:user:1:session:s-a",
                "agent:session:messages:1:s-a"), keys.getValue());
    }

    @Test
    void 删除必须按顺序传入锁key与数据key() {
        when(stringRedisTemplate.execute(any(RedisScript.class), any(), any(), anyList(), any(Object[].class)))
                .thenReturn(1L);

        SessionWriteResult result = protectedRedis.deleteIfHolder(
                "agent:session:lock:user:1:session:s-a", "token", "agent:draft:user:1:session:s-a");

        assertEquals(SessionWriteResult.SAVED, result);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), any(), any(), keys.capture(), any(Object[].class));

        assertEquals(List.of(
                "agent:session:lock:user:1:session:s-a",
                "agent:draft:user:1:session:s-a"), keys.getValue());
    }

    @Test
    void 脚本返回零表示令牌失效应拒绝写入() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), any(byte[].class), anyString())).thenReturn(0L);

        SessionWriteResult result = protectedRedis.writeIfHolder(
                "lock", "expired-token", "data-key", "value", Duration.ofMinutes(1));

        assertEquals(SessionWriteResult.LOCK_LOST, result);
    }

    @Test
    void Redis异常应返回存储不可用而不是伪称已保存() {
        when(stringRedisTemplate.execute(any(RedisScript.class), any(), any(), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));

        SessionWriteResult result = protectedRedis.writeIfHolder(
                "lock", "token", "data-key", "value", Duration.ofMinutes(1));

        assertEquals(SessionWriteResult.STORE_UNAVAILABLE, result);
    }
}

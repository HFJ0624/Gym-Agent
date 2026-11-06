package com.sau.gym.admin.agent.store;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:会话并发锁测试
 * 说明:
 * 覆盖同会话并发时后者被明确拒绝的要求。
 */
class AgentSessionLockServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void 同一会话第二个请求应被拒绝() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        //第一次加锁成功，第二次同一 key 加锁失败。
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true, false);

        AgentSessionLockService lockService = new AgentSessionLockService(redisTemplate);

        AgentSessionLockService.LockHandle first = lockService.tryLock(1L, "session-a", Duration.ofSeconds(60));
        AgentSessionLockService.LockHandle second = lockService.tryLock(1L, "session-a", Duration.ofSeconds(60));

        assertNotNull(first);
        assertNull(second, "同一会话的并发请求必须被明确拒绝");

        first.release();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 不同会话可以独立加锁() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        AgentSessionLockService lockService = new AgentSessionLockService(redisTemplate);

        AgentSessionLockService.LockHandle a = lockService.tryLock(1L, "session-a", Duration.ofSeconds(60));
        AgentSessionLockService.LockHandle b = lockService.tryLock(1L, "session-b", Duration.ofSeconds(60));

        assertNotNull(a);
        assertNotNull(b);

        a.release();
        b.release();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 释放后锁标记为不可用() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        AgentSessionLockService lockService = new AgentSessionLockService(redisTemplate);
        AgentSessionLockService.LockHandle handle = lockService.tryLock(1L, "session-a", Duration.ofSeconds(60));

        assertNotNull(handle);
        handle.release();

        //释放后不再认为锁有效，保证 finally 之后不会有续租任务继续跑。
        org.junit.jupiter.api.Assertions.assertFalse(handle.isAlive());
        org.junit.jupiter.api.Assertions.assertFalse(handle.isLeaseValid());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 续租成功应延长租约() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        //续租脚本返回 1 表示仍持有锁。
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        AgentSessionLockService lockService = new AgentSessionLockService(redisTemplate);
        AgentSessionLockService.LockHandle handle = lockService.tryLock(1L, "session-a", Duration.ofSeconds(60));

        assertNotNull(handle);
        assertTrue(handle.renewOnce(), "续租应成功");
        assertTrue(handle.isLeaseValid(), "续租成功后租约必须有效");
        assertTrue(handle.isAlive());

        handle.release();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 续租被拒绝应判定失效且不能覆盖新持有者() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        //续租脚本返回 0：锁已被新请求接管。
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);

        AgentSessionLockService lockService = new AgentSessionLockService(redisTemplate);
        AgentSessionLockService.LockHandle handle = lockService.tryLock(1L, "session-a", Duration.ofSeconds(60));

        assertNotNull(handle);
        assertFalse(handle.renewOnce(), "锁已被新持有者接管，续租必须失败");
        assertFalse(handle.isAlive());
        assertFalse(handle.isLeaseValid(), "失效后不得继续派发工具或提交会话状态");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 连续续租异常跨过租约有效期后应失效() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        //续租持续抛异常：短暂容忍，但租约到期后必须自然失效。
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));

        AgentSessionLockService lockService = new AgentSessionLockService(redisTemplate);
        AgentSessionLockService.LockHandle handle = lockService.tryLock(1L, "session-a", Duration.ofMillis(500));

        assertNotNull(handle);
        assertTrue(handle.isLeaseValid(), "刚加锁时租约应有效");

        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertFalse(handle.isLeaseValid(), "异常持续跨过租约有效期后不得继续视为有效");

        handle.release();
    }
}

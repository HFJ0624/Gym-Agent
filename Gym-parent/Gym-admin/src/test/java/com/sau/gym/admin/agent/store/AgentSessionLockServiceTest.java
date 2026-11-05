package com.sau.gym.admin.agent.store;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    }
}

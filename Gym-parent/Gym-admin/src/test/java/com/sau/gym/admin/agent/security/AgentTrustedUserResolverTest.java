package com.sau.gym.admin.agent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:可信身份解析测试
 * 说明:
 * 覆盖“前端传入他人 userId 不会以他人身份查询或执行工具”的要求。
 */
class AgentTrustedUserResolverTest {

    private AgentTrustedUserResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("{\"id\":2,\"username\":\"tester\"}");

        resolver = new AgentTrustedUserResolver(redisTemplate);
    }

    @Test
    void 登录态与query一致时正常放行() {
        assertEquals(2L, resolver.resolve(2L, "token-ok"));
    }

    @Test
    void query传入他人userId应被拒绝() {
        assertThrows(AgentIdentityMismatchException.class, () -> resolver.resolve(1L, "token-ok"));
    }

    @Test
    void 未登录时不采信query中的userId() {
        assertNull(resolver.resolve(1L, null));
    }

    @Test
    void 未登录且不传userId时为匿名() {
        assertNull(resolver.resolve(null, null));
    }

    @Test
    void 有token时以登录态为准() {
        assertEquals(2L, resolver.resolve(null, "token-ok"));
    }
}

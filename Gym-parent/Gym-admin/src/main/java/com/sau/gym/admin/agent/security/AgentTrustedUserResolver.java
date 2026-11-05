package com.sau.gym.admin.agent.security;

import com.alibaba.fastjson.JSON;
import com.sau.gym.model.entity.user.User;
import com.sau.gym.utils.AuthContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 作者:hfj
 * 功能:可信用户解析
 * 说明:
 * 1. 优先使用登录拦截器写入 ThreadLocal 的用户。
 * 2. 前台客服接口在 noAuthUrls 中，拦截器不会执行，因此再用 token 到 Redis 校验一次登录态。
 * 3. query 中的 userId 只做一致性校验，不作为身份来源。
 */
@Component
public class AgentTrustedUserResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentTrustedUserResolver.class);

    /**
     * 登录态 Redis key 前缀，与 LoginAuthInterceptor 保持一致。
     */
    private static final String LOGIN_KEY_PREFIX = "user:login:";

    private final StringRedisTemplate stringRedisTemplate;

    public AgentTrustedUserResolver(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 解析可信用户。
     *
     * @param requestedUserId 兼容老接口传入的 userId，可为空
     * @param token           前端 token，可为空
     * @return 可信用户ID；未登录返回 null
     * @throws AgentIdentityMismatchException userId 与登录态不一致
     */
    public Long resolve(Long requestedUserId, String token) {
        Long trustedUserId = resolveFromContext();

        if (trustedUserId == null) {
            trustedUserId = resolveFromToken(token);
        }

        if (requestedUserId != null && trustedUserId != null && !requestedUserId.equals(trustedUserId)) {
            throw new AgentIdentityMismatchException("请求用户与登录用户不一致，已拒绝处理。");
        }

        if (requestedUserId != null && trustedUserId == null) {
            //未登录时不采信前端传入的 userId，按匿名请求处理。
            log.warn("[AgentIdentity] 未登录请求携带 userId={}，已按匿名处理", requestedUserId);
        }

        return trustedUserId;
    }

    /**
     * 从登录拦截器上下文读取。
     */
    private Long resolveFromContext() {
        try {
            User user = AuthContextUtil.get();
            return user == null ? null : user.getId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 Redis 登录态读取。
     */
    private Long resolveFromToken(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }

        try {
            String json = stringRedisTemplate.opsForValue().get(LOGIN_KEY_PREFIX + token.trim());

            if (!StringUtils.hasText(json)) {
                return null;
            }

            User user = JSON.parseObject(json, User.class);

            return user == null ? null : user.getId();
        } catch (Exception e) {
            log.warn("[AgentIdentity] 解析登录态失败: {}", e.getMessage());
            return null;
        }
    }
}

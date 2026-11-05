package com.sau.gym.admin.agent.util;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 作者:hfj
 * 功能:会话标识规范化工具
 * 说明:
 * 1. sessionId 跨轮稳定，runId/traceId 每轮独立，二者不能混用。
 * 2. 老客户端不传 sessionId 时统一落到 default，不能每轮生成新 ID。
 * 3. 只允许字母、数字、下划线和短横线，长度不超过 64，避免被当成 Redis key 注入。
 */
public final class AgentSessionIdUtil {

    /**
     * 默认会话标识。
     * 用于兼容尚未传 sessionId 的老客户端。
     */
    public static final String DEFAULT_SESSION_ID = "default";

    /**
     * 允许的 sessionId 格式。
     */
    private static final Pattern ALLOWED_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * sessionId 最大长度。
     */
    private static final int MAX_LENGTH = 64;

    private AgentSessionIdUtil() {
    }

    /**
     * 规范化 sessionId。
     * 非法字符会被剔除，超长会被截断，最终仍为空时回落到 default。
     *
     * @param rawSessionId 前端传入的原始会话标识
     * @return 可直接用于 Redis key 的会话标识
     */
    public static String normalize(String rawSessionId) {
        if (!StringUtils.hasText(rawSessionId)) {
            return DEFAULT_SESSION_ID;
        }

        String value = rawSessionId.trim();

        if (ALLOWED_PATTERN.matcher(value).matches()) {
            return value;
        }

        //剔除不安全字符，再截断长度，保证 key 构造安全且稳定。
        String sanitized = value.replaceAll("[^A-Za-z0-9_-]", "");

        if (!StringUtils.hasText(sanitized)) {
            return DEFAULT_SESSION_ID;
        }

        if (sanitized.length() > MAX_LENGTH) {
            sanitized = sanitized.substring(0, MAX_LENGTH);
        }

        return sanitized;
    }

    /**
     * 判断是否为默认会话。
     */
    public static boolean isDefault(String sessionId) {
        return !StringUtils.hasText(sessionId) || DEFAULT_SESSION_ID.equals(sessionId.trim());
    }
}

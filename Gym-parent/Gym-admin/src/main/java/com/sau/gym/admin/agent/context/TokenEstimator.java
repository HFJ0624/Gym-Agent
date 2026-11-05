package com.sau.gym.admin.agent.context;

/**
 * 作者:hfj
 * 功能:上下文 Token 估算器
 * 说明:
 * 这里没有使用模型服务商的分词器，属于估算而非精确计数:
 * 1. ASCII 字符按 4 个字符约 1 Token 估算。
 * 2. 中文等非 ASCII 字符按 1 个字符约 1 Token 估算。
 * 该估算偏保守，用于预算裁剪足够安全，但不应作为计费依据。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /**
     * 估算一段文本的 Token 数。
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int ascii = 0;
        int nonAscii = 0;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) {
                ascii++;
            } else {
                nonAscii++;
            }
        }

        return (int) Math.ceil(ascii / 4.0D) + nonAscii;
    }
}

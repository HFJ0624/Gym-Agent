package com.sau.gym.admin.rag.retrieval;

import com.sau.gym.model.entity.rag.KnowledgeDocument;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 作者:hfj
 * 功能:知识内容指纹工具
 * 说明:
 * 1. 指纹只覆盖影响证据语义的字段：标题、正文、范围、来源类型、场馆、场地、主题。
 * 2. 不包含 indexedStatus、updateTime 等仅表示索引状态的字段，
 *    否则刚重建完成的索引也会被误判为内容过期。
 * 3. 所有索引入口共用同一指纹算法，保证普通入库与增量重建结果一致。
 */
public final class KnowledgeFingerprints {

    private KnowledgeFingerprints() {
    }

    /**
     * 计算知识文档的内容指纹。
     *
     * @param document 权威知识文档
     * @return 64 位十六进制指纹；文档为空时返回 null
     */
    public static String fingerprint(KnowledgeDocument document) {
        if (document == null) {
            return null;
        }

        String normalized = normalize(document.getTitle()) + "|"
                + normalize(document.getContent()) + "|"
                + document.getKnowledgeScope() + "|"
                + document.getSourceType() + "|"
                + document.getVenueId() + "|"
                + document.getCourtId() + "|"
                + normalize(document.getTopic());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));

            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }

            return builder.toString();
        } catch (Exception e) {
            //SHA-256 在标准 JDK 一定存在，这里只做防御。
            throw new IllegalStateException("计算知识内容指纹失败", e);
        }
    }

    /**
     * 取指纹前缀，用于拼进 evidenceId，保持标识短且稳定。
     */
    public static String shortFingerprint(KnowledgeDocument document) {
        String fingerprint = fingerprint(document);
        return fingerprint == null ? "" : fingerprint.substring(0, 16);
    }

    /**
     * 归一化文本：null 转空串，去首尾空白。
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

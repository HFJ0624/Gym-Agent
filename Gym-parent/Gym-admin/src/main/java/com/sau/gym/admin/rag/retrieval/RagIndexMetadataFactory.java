package com.sau.gym.admin.rag.retrieval;

import com.sau.gym.model.entity.rag.KnowledgeDocument;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:RAG 索引 metadata 构造工厂
 * 说明:
 * 所有实际索引入口（全量入库、增量重建）共用本工厂，
 * 保证 metadata 字段与内容指纹格式一致，避免不同入口产出不同结构。
 */
public final class RagIndexMetadataFactory {

    private RagIndexMetadataFactory() {
    }

    /**
     * 构造某个 chunk 的 metadata。
     *
     * @param document   权威知识文档
     * @param chunkIndex 片段下标
     * @return metadata 键值对（全部为 String）
     */
    public static Map<String, Object> buildMetadata(KnowledgeDocument document, int chunkIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        //知识文档基础信息
        metadata.put("docId", value(document.getId()));
        metadata.put("title", value(document.getTitle()));

        //知识范围和来源类型
        metadata.put("knowledgeScope", value(document.getKnowledgeScope()));
        metadata.put("sourceType", value(document.getSourceType()));

        //场馆信息
        metadata.put("venueId", value(document.getVenueId()));
        metadata.put("venueName", value(document.getVenueName()));

        //场地信息
        metadata.put("courtId", value(document.getCourtId()));
        metadata.put("courtName", value(document.getCourtName()));
        metadata.put("courtType", value(document.getCourtType()));

        //公告信息
        metadata.put("noticeId", value(document.getNoticeId()));

        //主题、标签、优先级
        metadata.put("topic", value(document.getTopic()));
        metadata.put("tags", value(document.getTags()));
        metadata.put("priority", value(document.getPriority()));

        //当前 chunk 下标
        metadata.put("chunkIndex", String.valueOf(chunkIndex));

        //内容指纹：检索阶段与权威文档重新计算的指纹比对，判定内容是否过期。
        metadata.put("contentHash", KnowledgeFingerprints.fingerprint(document));

        return metadata;
    }

    /**
     * 统一的空值处理，与原索引入口保持一致。
     */
    private static String value(Object object) {
        return object == null ? "" : String.valueOf(object);
    }
}

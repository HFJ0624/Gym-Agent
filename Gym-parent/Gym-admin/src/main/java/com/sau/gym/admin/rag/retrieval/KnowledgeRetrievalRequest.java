package com.sau.gym.admin.rag.retrieval;

import lombok.Builder;
import lombok.Data;

/**
 * 作者:hfj
 * 功能:知识检索请求
 */
@Data
@Builder
public class KnowledgeRetrievalRequest {

    /**
     * 用户问题。
     */
    private String question;

    /**
     * 当前或明确指定的场馆ID，可为空。
     */
    private Long venueId;

    /**
     * 当前或明确指定的场地ID，可为空。
     */
    private Long courtId;

    /**
     * 期望返回的证据数。
     * 为空时使用全局默认配置。
     */
    private Integer topK;

    /**
     * 相关性阈值。
     * 为空时使用全局默认配置。
     */
    private Double minScore;
}

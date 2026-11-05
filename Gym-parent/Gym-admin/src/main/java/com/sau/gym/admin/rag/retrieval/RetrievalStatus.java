package com.sau.gym.admin.rag.retrieval;

/**
 * 作者:hfj
 * 功能:知识检索结果状态
 * 说明:
 * 必须区分“正常命中”“没有资料”“范围不适用”“检索失败”，
 * 不能把服务异常一律当成无资料。
 */
public enum RetrievalStatus {

    /**
     * 检索到有效证据。
     */
    HIT,

    /**
     * 没有检索到任何候选或有效证据。
     */
    NO_EVIDENCE,

    /**
     * 指定了场馆/场地，但该范围内没有适用资料。
     * 与全库无资料区分，便于提示用户补充场馆信息。
     */
    INVALID_SCOPE,

    /**
     * 向量化阶段失败。
     */
    EMBEDDING_FAILED,

    /**
     * 向量检索或后处理阶段失败。
     */
    SEARCH_FAILED
}

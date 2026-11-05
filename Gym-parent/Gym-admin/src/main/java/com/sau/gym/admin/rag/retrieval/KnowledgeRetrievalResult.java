package com.sau.gym.admin.rag.retrieval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 作者:hfj
 * 功能:知识检索结果
 * 说明:
 * 只包含证据与过程信息，不包含任何生成式回答。
 */
@Data
public class KnowledgeRetrievalResult {

    /**
     * 检索状态。
     */
    private RetrievalStatus status;

    /**
     * 有效证据列表，已经过范围、启用状态与内容版本校验。
     */
    private List<KnowledgeEvidence> evidences = new ArrayList<>();

    /**
     * 向量检索返回的候选数量（过滤前）。
     */
    private int candidateCount;

    /**
     * 被过滤掉的候选数量。
     */
    private int rejectedCount;

    /**
     * 是否指定了场馆/场地范围。
     */
    private boolean scoped;

    /**
     * 总耗时，单位毫秒。
     */
    private long elapsedMs;

    /**
     * 向量化耗时，单位毫秒。
     */
    private long embeddingMs;

    /**
     * 向量检索耗时，单位毫秒。
     */
    private long searchMs;

    /**
     * 过程说明。
     * 正常时为空；失败或存在旧版本数据时给出可读原因，
     * 例如提示执行既有知识库重建入口。
     */
    private String detail;

    /**
     * 是否命中有效证据。
     */
    public boolean hasEvidence() {
        return status == RetrievalStatus.HIT && evidences != null && !evidences.isEmpty();
    }
}

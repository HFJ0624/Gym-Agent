package com.sau.gym.admin.rag.retrieval;

import lombok.Data;

/**
 * 作者:hfj
 * 功能:知识检索证据
 * 说明:
 * 一条证据对应向量库中的一个具体片段，并已经过范围、启用状态和内容版本校验。
 * evidenceId 由 docId + chunkIndex + 内容指纹前缀构成，可以长期稳定定位证据，
 * 不依赖本次检索列表的位置。
 */
@Data
public class KnowledgeEvidence {

    /**
     * 证据唯一标识，形如 docId:chunkIndex:hashPrefix。
     */
    private String evidenceId;

    /**
     * 权威知识文档 ID。
     */
    private Long docId;

    /**
     * 片段下标。
     */
    private Integer chunkIndex;

    /**
     * 文档标题。
     */
    private String title;

    /**
     * 片段正文。
     */
    private String text;

    /**
     * 适用场馆。
     */
    private Long venueId;

    private String venueName;

    /**
     * 适用场地。
     */
    private Long courtId;

    private String courtName;

    /**
     * 知识范围：1平台 2场馆 3场地 4公告 5FAQ。
     */
    private Integer knowledgeScope;

    /**
     * 范围中文名，便于展示。
     */
    private String knowledgeScopeName;

    /**
     * 来源类型，复用现有分类。
     */
    private Integer sourceType;

    /**
     * 主题。
     */
    private String topic;

    /**
     * 标签。
     */
    private String tags;

    /**
     * 向量相关性分数。
     * 只表示与问题的相似程度，不代表回答正确概率。
     */
    private Double score;

    /**
     * 内容指纹，与权威文档重新计算的指纹比对后写入。
     * 旧向量缺少指纹时为 null，表示版本未验证。
     */
    private String contentHash;

    /**
     * 文档更新时间，仅作展示信息，不作为版本判定依据。
     */
    private String updateTime;

    /**
     * 本次运行中的引用标签，例如 K1、K2。
     * 由 EvidenceRegistry 分配，服务端保存与真实证据的对应关系。
     */
    private String citationLabel;
}

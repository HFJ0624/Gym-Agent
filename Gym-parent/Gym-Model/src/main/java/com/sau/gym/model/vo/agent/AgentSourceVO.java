package com.sau.gym.model.vo.agent;

import lombok.Data;

import java.io.Serializable;

/**
 * 作者:hfj
 * 功能:客服回答来源
 * 说明:
 * 来源由后端根据回答实际引用的证据组装，
 * 只包含经过范围、启用状态与内容版本校验的证据。
 */
@Data
public class AgentSourceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 引用标签，例如 K1。
     */
    private String label;

    /**
     * 证据唯一标识。
     */
    private String evidenceId;

    /**
     * 权威文档 ID。
     */
    private Long docId;

    /**
     * 文档标题。
     */
    private String title;

    /**
     * 适用范围名称。
     */
    private String scopeName;

    /**
     * 适用场馆名称。
     */
    private String venueName;

    /**
     * 适用场地名称。
     */
    private String courtName;

    /**
     * 片段预览。
     */
    private String preview;

    /**
     * 向量相关性分数。
     */
    private Double score;

    /**
     * 内容指纹前缀，用于区分资料版本。
     */
    private String contentHash;
}

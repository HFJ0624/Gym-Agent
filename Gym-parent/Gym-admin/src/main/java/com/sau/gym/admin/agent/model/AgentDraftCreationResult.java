package com.sau.gym.admin.agent.model;

import lombok.Data;

import java.util.Map;

/**
 * 作者:hfj
 * 功能:草稿创建结果
 * 说明:
 * 除了给用户看的文案，还要把确认码和结构化草稿数据一并返回，
 * 让工具结果能直接组装前端卡片，不需要从文本里反解析。
 */
@Data
public class AgentDraftCreationResult {

    /**
     * 给用户看的草稿文案。
     */
    private final String text;

    /**
     * 本次草稿的确认码，创建失败时为空。
     */
    private final String confirmToken;

    /**
     * 结构化草稿数据。
     */
    private final Map<String, Object> data;

    /**
     * 只返回文案和确认码，用于创建失败等场景。
     */
    public AgentDraftCreationResult(String text, String confirmToken) {
        this(text, confirmToken, null);
    }

    public AgentDraftCreationResult(String text, String confirmToken, Map<String, Object> data) {
        this.text = text;
        this.confirmToken = confirmToken;
        this.data = data;
    }
}

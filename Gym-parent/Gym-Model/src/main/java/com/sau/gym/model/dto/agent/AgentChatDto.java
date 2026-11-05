package com.sau.gym.model.dto.agent;

/**
 * 作者:hfj
 * 功能:
 * 日期: 2026/5/5 15:25
 */

import lombok.Data;

/**
 * Agent 聊天请求参数。
 */
@Data
public class AgentChatDto {

    /**
     * 用户消息
     */
    private String message;

    /**
     * 当前场馆ID，可选
     */
    private Long venueId;

    /**
     * 当前场地ID，可选
     */
    private Long courtId;

    /**
     * 会话标识，可选。
     * 前端同一段连续会话复用同一个 sessionId；不传时后端使用 default 会话兼容。
     * 与每轮独立的 runId/traceId 不同，sessionId 跨轮稳定。
     */
    private String sessionId;
}

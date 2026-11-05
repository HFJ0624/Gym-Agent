package com.sau.gym.admin.agent.memory.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 作者:hfj
 * 功能:Agent业务上下文对象
 * 说明:
 * LangChain4j 自带的 ChatMemory 主要保存“聊天消息”。
 * 但体育场馆预约业务里，还需要记住一些结构化信息，例如：
 * 1. 用户最近看的场馆
 * 2. 用户最近看的场地
 * 3. 用户最近想预约的日期和时间段
 * 4. 用户最近的业务意图
 * 这些信息不能只交给大模型记忆。
 * 因为后端工具调用时，需要明确的 venueId、courtId、date、startTime、endTime。
 * 所以这里单独设计一个业务上下文对象，用 Redis 保存。
 * 日期: 2026/5/6 20:05
 */
@Data
public class AgentBusinessContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID。
     * 当前项目里可以先按 userId 维度保存上下文。
     * 后续如果要支持一个用户多个会话窗口，可以再加 sessionId。
     */
    private Long userId;

    /**
     * 会话ID。
     * 业务上下文按 userId + sessionId 维度保存，避免不同会话串用最近场馆或场地。
     */
    private String sessionId;

    /**
     * 最近一次上下文中的场馆ID。
     * 来源可能是：
     * 1. 前端页面传入的 venueId
     * 2. Agent 查询场馆后的结果
     * 3. 预约草稿中的 venueId
     */
    private Long lastVenueId;

    /**
     * 最近一次上下文中的场馆名称。
     * 主要用于拼接给大模型看，提高可读性。
     */
    private String lastVenueName;

    /**
     * 最近一次上下文中的场地ID。
     */
    private Long lastCourtId;

    /**
     * 最近一次上下文中的场地名称。
     */
    private String lastCourtName;

    /**
     * 最近一次上下文中的场地类型。
     * 例如：篮球场、羽毛球场、足球场。
     */
    private String lastCourtType;

    /**
     * 最近一次用户提到或选择的预约日期。
     * 这里用字符串是为了避免 Redis JSON 序列化 LocalDate 的兼容问题。
     * 格式建议：yyyy-MM-dd。
     */
    private String lastBookingDate;

    /**
     * 最近一次用户提到或选择的开始时间。
     * 格式建议：HH:mm:ss。
     */
    private String lastStartTime;

    /**
     * 最近一次用户提到或选择的结束时间。
     * 格式建议：HH:mm:ss。
     */
    private String lastEndTime;

    /**
     * 最近一次意图。
     * 例如：
     * BOOKING_CREATE：创建预约
     * BOOKING_QUERY：查询我的预约
     * RAG_QA：规则问答
     * VENUE_QUERY：查询场馆
     */
    private String lastIntent;

    /**
     * 最近一次用户原始消息。
     * 方便排查上下文来源。
     */
    private String lastUserMessage;

    /**
     * 最近一次更新时间。
     */
    private LocalDateTime updatedAt;
}

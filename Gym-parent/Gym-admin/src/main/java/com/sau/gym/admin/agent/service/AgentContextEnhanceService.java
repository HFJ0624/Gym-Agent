package com.sau.gym.admin.agent.service;

import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.model.dto.agent.AgentChatDto;

public interface AgentContextEnhanceService {

    /**
     * 在调用 Agent 之前准备业务上下文。
     *
     * 主要做三件事：
     * 1. 从 Redis 中读取当前会话已有上下文
     * 2. 合并本次前端传入的 venueId / courtId
     * 3. 从用户消息中解析日期、时间段、意图等信息
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param message   用户本轮输入消息
     * @param dto       前端传入的 Agent 请求参数
     * @return 更新后的业务上下文
     */
    AgentBusinessContext prepareBeforeAgent(Long userId, String sessionId, String message, AgentChatDto dto,
                                            com.sau.gym.admin.agent.store.SessionWriteTicket ticket);

    /**
     * 在 Agent 调用完成后刷新业务上下文。
     *
     * 典型场景：
     * Agent 工具生成了预约草稿，草稿中包含 venueId、courtId、date、startTime、endTime。
     * 这时应该把草稿里的准确信息反写到 Redis 上下文中，
     * 方便用户下一轮说“时间改成8点到10点”“还是这个场地”。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     */
    void refreshAfterAgent(Long userId, String sessionId, com.sau.gym.admin.agent.store.SessionWriteTicket ticket);

    /**
     * 构造传给大模型的上下文提示词。
     *
     * 该方法会把结构化上下文转换成文本，例如：
     * 最近场馆ID、最近场地ID、最近预约日期、最近时间段等。
     *
     * @param context 业务上下文
     * @return 拼接好的上下文提示词
     */
    String buildContextPrompt(AgentBusinessContext context);

    /**
     * 获取有效场馆ID。
     *
     * 优先级：
     * 1. 本次前端请求传入的 venueId
     * 2. Redis 上下文中的 lastVenueId
     *
     * @param dto     前端请求参数
     * @param context Redis 业务上下文
     * @return 有效场馆ID
     */
    Long getEffectiveVenueId(AgentChatDto dto, AgentBusinessContext context);

    /**
     * 获取有效场地ID。
     *
     * 优先级：
     * 1. 本次前端请求传入的 courtId
     * 2. Redis 上下文中的 lastCourtId
     *
     * @param dto     前端请求参数
     * @param context Redis 业务上下文
     * @return 有效场地ID
     */
    Long getEffectiveCourtId(AgentChatDto dto, AgentBusinessContext context);
}

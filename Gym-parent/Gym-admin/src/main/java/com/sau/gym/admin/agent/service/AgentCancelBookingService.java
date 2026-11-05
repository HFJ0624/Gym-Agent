package com.sau.gym.admin.agent.service;

import com.sau.gym.admin.agent.model.AgentDraftCreationResult;

public interface AgentCancelBookingService {

    /**
     * 查询当前用户可取消预约列表。
     *
     * @param userId 用户ID
     * @return 给用户看的文本
     */
    String queryCancelableBookings(Long userId);

    /**
     * 生成取消预约草稿。
     * 草稿按 userId + sessionId 保存，用户确认后才能真实取消。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param bookingId 预约订单ID
     * @param reason    取消原因
     * @return 草稿文案与确认码
     */
    AgentDraftCreationResult createCancelBookingDraft(Long userId, String sessionId, Long bookingId, String reason);

    /**
     * 用户确认取消预约。
     *
     * @param userId       用户ID
     * @param sessionId    会话ID
     * @param confirmToken 确认码
     * @return 执行结果
     */
    String confirmCancelBooking(Long userId, String sessionId, String confirmToken);
}

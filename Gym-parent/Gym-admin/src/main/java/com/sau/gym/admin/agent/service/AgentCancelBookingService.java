package com.sau.gym.admin.agent.service;

import com.sau.gym.admin.agent.model.AgentDraftCreationResult;
import com.sau.gym.admin.agent.store.SessionWriteTicket;

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
     * 草稿写入使用持有者保护（Redis 原子校验锁令牌）。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param bookingId 预约订单ID
     * @param reason    取消原因
     * @param ticket    会话受保护写入凭证
     * @return 草稿文案与确认码
     */
    AgentDraftCreationResult createCancelBookingDraft(Long userId, String sessionId, Long bookingId,
                                                      String reason, SessionWriteTicket ticket);

    /**
     * 用户确认取消预约。
     * 确认成功后的草稿清理使用持有者保护。
     *
     * @param userId       用户ID
     * @param sessionId    会话ID
     * @param confirmToken 确认码
     * @param ticket       会话受保护写入凭证
     * @return 执行结果
     */
    String confirmCancelBooking(Long userId, String sessionId, String confirmToken, SessionWriteTicket ticket);
}

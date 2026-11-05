package com.sau.gym.admin.agent.service.impl;

import com.sau.gym.admin.agent.model.AgentCancelableBookingVO;
import com.sau.gym.admin.agent.model.AgentDraftCreationResult;
import com.sau.gym.admin.agent.service.AgentCancelBookingService;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.PendingDraft;
import com.sau.gym.admin.enums.PendingDraftType;
import com.sau.gym.admin.agent.util.AgentConfirmTokenUtil;
import com.sau.gym.admin.mapper.BookingRefundRequestMapper;
import com.sau.gym.admin.mapper.CourtBookingMapper;
import com.sau.gym.admin.service.CourtBookingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:Agent取消预约服务实现
 * 核心原则：
 * 1. 查询预约可以直接做
 * 2. 生成取消草稿可以让 Agent 工具调用
 * 3. 真正取消必须用户确认
 * 4. 涉及退款只生成退款申请，不直接退款到账
 * 日期: 2026/5/9 10:43
 */
@Service
public class AgentCancelBookingServiceImpl implements AgentCancelBookingService {

    private final CourtBookingMapper courtBookingMapper;
    private final BookingRefundRequestMapper refundRequestMapper;
    private final AgentDraftStore draftStore;

    private final CourtBookingService courtBookingService;

    public AgentCancelBookingServiceImpl(CourtBookingMapper courtBookingMapper,
                                         BookingRefundRequestMapper refundRequestMapper,
                                         AgentDraftStore draftStore,
                                         CourtBookingService courtBookingService) {
        this.courtBookingMapper = courtBookingMapper;
        this.refundRequestMapper = refundRequestMapper;
        this.draftStore = draftStore;
        this.courtBookingService = courtBookingService;
    }

    /**
     * 查询当前用户可取消预约。
     */
    @Override
    public String queryCancelableBookings(Long userId) {
        if (userId == null) {
            return "请先登录后再查询可取消预约。";
        }

        List<AgentCancelableBookingVO> bookings = courtBookingMapper.selectAgentUserBookings(userId);

        if (bookings == null || bookings.isEmpty()) {
            return "你当前没有预约记录。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("我查到你的预约记录如下：\n");

        int count = 0;

        for (AgentCancelableBookingVO booking : bookings) {
            if (booking == null) {
                continue;
            }

            boolean cancelable = isCancelable(booking);

            //只展示可取消预约。
            if (!cancelable) {
                continue;
            }

            count++;

            builder.append("\n")
                    .append(count)
                    .append(". 预约ID：").append(booking.getBookingId()).append("\n")
                    .append("   场馆：").append(nullToEmpty(booking.getVenueName())).append("\n")
                    .append("   场地：").append(nullToEmpty(booking.getCourtName())).append("\n")
                    .append("   类型：").append(nullToEmpty(booking.getCourtType())).append("\n")
                    .append("   日期：").append(booking.getBookingDate()).append("\n")
                    .append("   时间：").append(booking.getStartTime()).append(" - ").append(booking.getEndTime()).append("\n")
                    .append("   金额：").append(booking.getTotalPrice()).append("\n")
                    .append("   状态：").append(statusText(booking.getStatus())).append("\n")
                    .append("   如果要取消，请回复：取消预约 ").append(booking.getBookingId()).append("\n");
        }

        if (count == 0) {
            return "你当前没有可取消的预约。已开始、已完成或已取消的预约不能通过 Agent 取消。";
        }

        return builder.toString();
    }

    /**
     * 生成取消预约草稿。
     */
    @Override
    public AgentDraftCreationResult createCancelBookingDraft(Long userId, String sessionId, Long bookingId, String reason) {
        if (userId == null) {
            return new AgentDraftCreationResult("请先登录后再取消预约。", null);
        }

        if (bookingId == null) {
            return new AgentDraftCreationResult("请提供要取消的预约ID。例如：取消预约 12。", null);
        }

        AgentCancelableBookingVO booking = courtBookingMapper.selectAgentBookingDetail(userId, bookingId);

        if (booking == null) {
            return new AgentDraftCreationResult("未找到该预约，或者该预约不属于当前用户。", null);
        }

        if (!isCancelable(booking)) {
            return new AgentDraftCreationResult(
                    "该预约当前不能取消。订单状态：" + statusText(booking.getStatus())
                            + "。已取消、已完成或已开始的预约不能取消。",
                    null
            );
        }

        String finalReason = StringUtils.hasText(reason) ? reason.trim() : "用户通过 Agent 取消预约";

        boolean needRefund = needRefundRequest(booking);

        String confirmToken = AgentConfirmTokenUtil.generateToken();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bookingId", booking.getBookingId());
        data.put("orderNo", booking.getOrderNo());
        data.put("userId", userId);
        data.put("venueName", booking.getVenueName());
        data.put("courtId", booking.getCourtId());
        data.put("courtName", booking.getCourtName());
        data.put("courtType", booking.getCourtType());
        data.put("bookingDate", String.valueOf(booking.getBookingDate()));
        data.put("startTime", String.valueOf(booking.getStartTime()));
        data.put("endTime", String.valueOf(booking.getEndTime()));
        data.put("totalPrice", booking.getTotalPrice());
        data.put("status", booking.getStatus());
        data.put("reason", finalReason);
        data.put("needRefund", needRefund);

        //保存取消预约草稿到 Redis。用户必须带确认码确认后，才会真正取消预约。
        draftStore.save(userId, sessionId, new PendingDraft(
                PendingDraftType.CANCEL_BOOKING,
                data,
                LocalDateTime.now(),
                confirmToken
        ));

        String text = "已为你生成取消预约草稿：\n"
                + "预约ID：" + booking.getBookingId() + "\n"
                + "场馆：" + nullToEmpty(booking.getVenueName()) + "\n"
                + "场地：" + nullToEmpty(booking.getCourtName()) + "\n"
                + "类型：" + nullToEmpty(booking.getCourtType()) + "\n"
                + "日期：" + booking.getBookingDate() + "\n"
                + "时间：" + booking.getStartTime() + " - " + booking.getEndTime() + "\n"
                + "金额：" + booking.getTotalPrice() + "\n"
                + "取消原因：" + finalReason + "\n"
                + "是否生成退款申请：" + (needRefund ? "是" : "否") + "\n"
                + "确认码：" + confirmToken + "\n"
                + "如果确认取消，请回复：确认取消预约 " + confirmToken + "\n"
                + "如果放弃，请回复：取消\n"
                + "注意：该取消草稿将在系统设置的草稿过期时间后自动失效。";

        return new AgentDraftCreationResult(text, confirmToken, data);
    }

    /**
     * 用户确认取消预约。
     * 使用事务：
     * 1. 更新预约状态
     * 2. 插入退款申请
     * 两步要么都成功，要么都失败。
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public String confirmCancelBooking(Long userId, String sessionId, String confirmToken) {
        if (userId == null) {
            return "请先登录后再确认取消预约。";
        }

        PendingDraft draft = draftStore.get(userId, sessionId);

        if (draft == null || draft.type() != PendingDraftType.CANCEL_BOOKING) {
            return "当前没有待确认的取消预约草稿。";
        }

        if (!StringUtils.hasText(confirmToken)) {
            return "请带上确认码，例如：确认取消预约 " + draft.confirmToken();
        }

        if (!draft.confirmToken().equals(confirmToken.trim())) {
            return "确认码错误，请核对后重新输入。正确格式为：确认取消预约 " + draft.confirmToken();
        }

        Map data = draft.data();

        Long bookingId = toLong(data.get("bookingId"));
        String reason = String.valueOf(data.get("reason"));

        if (bookingId == null) {
            return "取消草稿数据异常：缺少预约ID。";
        }

        //再查一次数据库。不能完全相信 Redis 草稿，因为订单状态可能已经变化。
        AgentCancelableBookingVO booking = courtBookingMapper.selectAgentBookingDetail(userId, bookingId);

        if (booking == null) {
            draftStore.clear(userId, sessionId);
            return "预约不存在或不属于当前用户，已清除取消草稿。";
        }

        if (!isCancelable(booking)) {
            draftStore.clear(userId, sessionId);
            return "该预约当前已不能取消，订单状态：" + statusText(booking.getStatus()) + "。已清除取消草稿。";
        }

        //更新预约状态为已取消。
        int rows = courtBookingMapper.updateAgentCancelBooking(userId, bookingId, reason);

        if (rows <= 0) {
            throw new RuntimeException("取消预约失败，订单可能已被处理，请刷新后重试。");
        }

        //调用统一取消预约逻辑
        courtBookingService.cancelOrderByUserId(
                userId,
                bookingId,
                reason,
                "AGENT"
        );

        draftStore.clear(userId, sessionId);

        return "取消预约成功：\n"
                + "预约ID：" + booking.getBookingId() + "\n"
                + "场馆：" + nullToEmpty(booking.getVenueName()) + "\n"
                + "场地：" + nullToEmpty(booking.getCourtName()) + "\n"
                + "日期：" + booking.getBookingDate() + "\n"
                + "时间：" + booking.getStartTime() + " - " + booking.getEndTime() + "\n"
                + "退款处理：已生成退款申请，等待后台审核。";
    }

    /**
     * 判断预约是否允许取消。
     * 第一版规则：
     * 1. 已取消不能取消
     * 2. 已完成不能取消
     * 3. 已开始或已过期不能取消
     * 4. 状态 0 / 1 可以取消
     */
    private boolean isCancelable(AgentCancelableBookingVO booking) {
        if (booking == null) {
            return false;
        }

        Integer status = booking.getStatus();

        if (status == null) {
            return false;
        }

        if (status == 2 || status == 3) {
            return false;
        }

        if (!(status == 0 || status == 1)) {
            return false;
        }

        LocalDate bookingDate = booking.getBookingDate();
        LocalTime startTime = booking.getStartTime();

        if (bookingDate == null || startTime == null) {
            return false;
        }

        LocalDateTime bookingStart = LocalDateTime.of(bookingDate, startTime);

        return bookingStart.isAfter(LocalDateTime.now());
    }

    /**
     * 判断是否需要生成退款申请。
     *
     * 说明：
     * 你当前项目创建预约时已经扣余额，但订单 status 设置为 0。
     * 所以第一版为了安全，status=0 或 status=1 且金额大于 0 都生成退款申请。
     *
     * 如果后面你把支付状态修正为：
     * 0 = 未支付，不扣钱
     * 1 = 已支付，已扣钱
     *
     * 那这里可以改成只有 status == 1 才生成退款申请。
     */
    private boolean needRefundRequest(AgentCancelableBookingVO booking) {
        if (booking == null || booking.getTotalPrice() == null) {
            return false;
        }

        if (booking.getTotalPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        Integer status = booking.getStatus();

        return status != null && (status == 0 || status == 1);
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }

        return switch (status) {
            case 0 -> "待支付/已创建";
            case 1 -> "已支付";
            case 2 -> "已取消";
            case 3 -> "已完成";
            default -> "未知状态";
        };
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}

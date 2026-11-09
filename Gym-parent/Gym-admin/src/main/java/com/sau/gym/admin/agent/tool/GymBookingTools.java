package com.sau.gym.admin.agent.tool;

import com.sau.gym.admin.agent.service.AgentToolGuardService;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.PendingDraft;
import com.sau.gym.admin.enums.PendingDraftType;
import com.sau.gym.admin.service.CourtBookingService;
import com.sau.gym.model.dto.venue.BookingDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:预约确认工具
 * 说明:
 * 1. 草稿生成已迁移到 CreateBookingDraftToolExecutor（自建 Function Calling 链路），
 *    旧的 LangChain4j @Tool 包装方法 createBookingDraftByContext 已删除。
 * 2. 本类只保留确认预约方法，由 AgentConfirmationWorkflow 在用户确认时调用，
 *    不向模型开放；真实预约仍走 CourtBookingService 原有业务校验与事务。
 * 日期: 2026/4/23 14:46
 */
@Component
public class GymBookingTools {

    /**
     * 预约业务 Service：confirmPendingBooking 执行真实预约。
     */
    private final CourtBookingService courtBookingService;

    /**
     * 草稿缓存：confirmPendingBooking 读取与受保护清理草稿。
     */
    private final AgentDraftStore draftStore;

    public GymBookingTools(CourtBookingService courtBookingService, AgentDraftStore draftStore) {
        this.courtBookingService = courtBookingService;
        this.draftStore = draftStore;
    }

    /**
     * 确认预约草稿并执行真实预约。
     *
     * @param userId       可信用户ID（来自 Workflow，不来自模型）
     * @param sessionId    会话ID
     * @param confirmToken 用户输入的确认码；必须与当前草稿的确认码一致，
     *                     不匹配时拒绝执行且不产生任何业务写入
     * @param ticket       会话受保护写入凭证（来自真实锁句柄）：
     *                     确认成功后用于受保护清理草稿，
     *                     条件写被拒时不清草稿，重复确认由业务校验拦截
     * @return 用户可读的办理结果；预约失败原因来自业务异常信息
     */
    public String confirmPendingBooking(Long userId, String sessionId, String confirmToken,
                                        com.sau.gym.admin.agent.store.SessionWriteTicket ticket) {
        PendingDraft draft = draftStore.get(userId, sessionId);

        if (draft == null || draft.type() != PendingDraftType.BOOKING) {
            return "当前没有待确认的预约草稿。";
        }

        if (confirmToken == null || confirmToken.trim().isEmpty()) {
            return "请带上确认码，例如：确认预约 " + draft.confirmToken();
        }

        if (!draft.confirmToken().equals(confirmToken.trim())) {
            return "确认码错误，请核对后重新输入。正确格式为：确认预约 " + draft.confirmToken();
        }

        try {
            Map<String, Object> data = draft.data();

            Long courtId = ((Number) data.get("courtId")).longValue();
            String venueName = String.valueOf(data.get("venueName"));
            String courtName = String.valueOf(data.get("courtName"));
            String date = String.valueOf(data.get("date"));
            String startTime = String.valueOf(data.get("startTime"));
            String endTime = String.valueOf(data.get("endTime"));

            BookingDto bookingDto = new BookingDto();
            bookingDto.setCourtId(courtId);
            bookingDto.setUserId(userId);
            bookingDto.setBookingDate(LocalDate.parse(date));
            bookingDto.setStartTime(LocalTime.parse(startTime));
            bookingDto.setEndTime(LocalTime.parse(endTime));
            bookingDto.setHoursPrice((BigDecimal) data.get("hoursPrice"));
            bookingDto.setRemark("智能客服预约-" + venueName + "-" + courtName);

            //真实预约业务（Service 内部完成最终校验与事务）。
            courtBookingService.saveCourtBook(bookingDto);

            //预约已提交：受保护清理草稿；清理被拒不影响预约结果，重复确认由业务校验拦截。
            com.sau.gym.admin.agent.store.SessionWriteResult clearResult =
                    draftStore.clearIfHolder(userId, sessionId, ticket);

            if (clearResult != com.sau.gym.admin.agent.store.SessionWriteResult.SAVED) {
                return "预约已提交，但清理本地草稿未获授权，重复确认会由业务校验拦截。";
            }

            return "预约成功：\n"
                    + "场馆：" + venueName + "\n"
                    + "场地：" + courtName + "\n"
                    + "日期：" + date + "\n"
                    + "开始时间：" + startTime + "\n"
                    + "结束时间：" + endTime + "\n"
                    + "已调用系统原有预约逻辑完成下单。";
        } catch (Exception e) {
            //业务异常不暴露堆栈，只返回可读原因。
            return "预约失败：" + e.getMessage();
        }
    }
}

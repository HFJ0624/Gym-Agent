package com.sau.gym.admin.agent.tool.executor.impl;

import com.sau.gym.admin.agent.service.AgentToolGuardService;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.PendingDraft;
import com.sau.gym.admin.agent.tool.AgentToolCodes;
import com.sau.gym.admin.agent.tool.executor.AbstractGymAgentToolExecutor;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.executor.AgentToolParamDefinition;
import com.sau.gym.admin.agent.util.AgentConfirmTokenUtil;
import com.sau.gym.admin.enums.AgentRiskLevel;
import com.sau.gym.admin.enums.PendingDraftType;
import com.sau.gym.admin.mapper.CourtBookingMapper;
import com.sau.gym.admin.mapper.CourtMapper;
import com.sau.gym.admin.mapper.VenueMapper;
import com.sau.gym.model.entity.venue.Venue;
import com.sau.gym.model.vo.court.CourtBookVO;
import com.sau.gym.model.vo.court.CourtVO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:生成预约草稿工具执行器
 * 作用:
 * 统一封装“生成预约草稿”的工具元信息和执行逻辑。
 * 日期: 2026/5/27 15:29
 */
@Component
public class CreateBookingDraftToolExecutor extends AbstractGymAgentToolExecutor {

    /**
     * 场馆 Mapper。
     * 用于查询系统中的场馆列表，并根据 venueId 找到当前场馆。
     */
    private final VenueMapper venueMapper;

    /**
     * 场地 Mapper。
     * 用于查询某个场馆下的场地列表，并根据 courtId 找到当前场地。
     */
    private final CourtMapper courtMapper;

    /**
     * 预约 Mapper。
     * 用于查询某个场地在某一天已经被预约的时间段，
     * 从而判断新预约是否和已有预约冲突。
     */
    private final CourtBookingMapper courtBookingMapper;

    /**
     * Agent 草稿缓存。
     * 用 Redis 保存待用户确认的预约草稿。
     */
    private final AgentDraftStore draftStore;

    public CreateBookingDraftToolExecutor(
            AgentToolGuardService agentToolGuardService,
            VenueMapper venueMapper,
            CourtMapper courtMapper,
            CourtBookingMapper courtBookingMapper,
            AgentDraftStore draftStore
    ) {
        super(agentToolGuardService);
        this.venueMapper = venueMapper;
        this.courtMapper = courtMapper;
        this.courtBookingMapper = courtBookingMapper;
        this.draftStore = draftStore;
    }

    @Override
    public String toolCode() {
        return AgentToolCodes.CREATE_BOOKING_DRAFT;
    }

    @Override
    public String toolName() {
        return "生成预约草稿";
    }

    @Override
    public String description() {
        return "根据场馆、场地、日期和时间段生成预约草稿。该工具不会真实下单，必须等待用户确认。";
    }

    @Override
    public AgentRiskLevel riskLevel() {
        return AgentRiskLevel.MEDIUM;
    }

    @Override
    public boolean needLogin() {
        return true;
    }

    @Override
    public boolean needConfirm() {
        return false;
    }

    @Override
    public int rateLimitSeconds() {
        return 2;
    }

    @Override
    public List<AgentToolParamDefinition> paramDefinitions() {
        return Arrays.asList(
                new AgentToolParamDefinition(
                        "venueId",
                        "场馆ID",
                        "Long",
                        true,
                        "1"
                ),
                new AgentToolParamDefinition(
                        "courtId",
                        "场地ID",
                        "Long",
                        true,
                        "10"
                ),
                new AgentToolParamDefinition(
                        "bookingDate",
                        "预约日期",
                        "String",
                        true,
                        "2026-05-27"
                ),
                new AgentToolParamDefinition(
                        "startTime",
                        "开始时间",
                        "String",
                        true,
                        "19:00:00"
                ),
                new AgentToolParamDefinition(
                        "endTime",
                        "结束时间",
                        "String",
                        true,
                        "20:00:00"
                )
        );
    }

    @Override
    protected AgentToolExecuteResult doExecute(AgentToolExecuteContext context) {

        //1. 从统一上下文中读取用户ID。
        Long userId = context.getUserId();

        //2. 从工具参数中读取预约所需信息。
        Long venueId = context.getLongParam("venueId");
        Long courtId = context.getLongParam("courtId");
        String bookingDate = context.getStringParam("bookingDate");
        String startTime = context.getStringParam("startTime");
        String endTime = context.getStringParam("endTime");

        //3. userId 兜底校验。
        if (userId == null) {
            return AgentToolExecuteResult.permissionDenied(
                    "当前用户信息缺失，请先登录后再预约。"
            );
        }

        //4. 预约日期格式校验。
        LocalDate localDate;
        try {
            localDate = LocalDate.parse(bookingDate);
        } catch (Exception e) {
            return AgentToolExecuteResult.paramError(
                    "预约日期格式错误，请使用 yyyy-MM-dd，例如 2026-05-08。",
                    "Invalid bookingDate: " + bookingDate
            );
        }

        //5. 预约时间格式校验。
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(startTime);
            end = LocalTime.parse(endTime);
        } catch (Exception e) {
            return AgentToolExecuteResult.paramError(
                    "时间格式错误，请使用 HH:mm:ss，例如 19:00:00。",
                    "Invalid startTime or endTime, startTime=" + startTime + ", endTime=" + endTime
            );
        }

        //6. 校验开始时间必须早于结束时间。
        if (!start.isBefore(end)) {
            return AgentToolExecuteResult.paramError(
                    "预约开始时间必须早于结束时间。",
                    "startTime must be before endTime"
            );
        }

        //7. 校验预约时长。
        long minutes = Duration.between(start, end).toMinutes();

        if (minutes < 60) {
            return AgentToolExecuteResult.paramError(
                    "预约时长不能少于1小时。",
                    "booking duration minutes=" + minutes
            );
        }

        //8. 查询当前场馆。
        List<Venue> venueList = venueMapper.findAllVenue();

        if (venueList == null || venueList.isEmpty()) {
            return AgentToolExecuteResult.failed(
                    "系统中暂无可预约场馆。",
                    "venueList is empty"
            );
        }

        Venue targetVenue = venueList.stream()
                .filter(v -> v != null && venueId.equals(v.getId()))
                .findFirst()
                .orElse(null);

        if (targetVenue == null) {
            return AgentToolExecuteResult.paramError(
                    "未找到当前页面对应的场馆，venueId=" + venueId + "。",
                    "venue not found, venueId=" + venueId
            );
        }

        //9. 查询当前场馆下的场地列表。
        List<CourtVO> courtList = courtMapper.getAllCourt(targetVenue.getId());

        if (courtList == null || courtList.isEmpty()) {
            return AgentToolExecuteResult.failed(
                    "场馆〖" + targetVenue.getVenueName() + "〗下暂无可预约场地。",
                    "courtList is empty, venueId=" + targetVenue.getId()
            );
        }

        //10. 根据 courtId 精确匹配当前场地。
        CourtVO targetCourt = courtList.stream()
                .filter(c -> c != null && courtId.equals(c.getId()))
                .findFirst()
                .orElse(null);

        if (targetCourt == null) {
            return AgentToolExecuteResult.paramError(
                    "场馆〖" + targetVenue.getVenueName() + "〗下未找到当前页面对应的场地，courtId=" + courtId + "。",
                    "court not found, courtId=" + courtId
            );
        }


        //11. 校验场地价格。
        if (targetCourt.getPrice() == null) {
            return AgentToolExecuteResult.failed(
                    "当前场地暂未配置预约价格，无法生成预约草稿。",
                    "court price is null, courtId=" + targetCourt.getId()
            );
        }

        //12. 查询当前场地在该日期下的已预约时间段，并检查是否冲突。
        List<CourtBookVO> bookedList = courtBookingMapper.selectBookTime(
                localDate,
                targetCourt.getId()
        );

        if (bookedList != null && !bookedList.isEmpty()) {
            for (CourtBookVO courtBookVO : bookedList) {
                if (courtBookVO == null
                        || courtBookVO.getStartTime() == null
                        || courtBookVO.getEndTime() == null) {
                    continue;
                }

                boolean isOverlap =
                        start.isBefore(courtBookVO.getEndTime())
                                && end.isAfter(courtBookVO.getStartTime());

                if (isOverlap) {
                    return AgentToolExecuteResult.failed(
                            "预约时间段与已有预约冲突，日期："
                                    + bookingDate
                                    + "，开始时间："
                                    + startTime
                                    + "，结束时间："
                                    + endTime
                                    + "。请更换时间段。",
                            "booking time overlap, courtId=" + targetCourt.getId()
                    );
                }
            }
        }

        //13. 计算总价。
        BigDecimal totalPrice = targetCourt.getPrice()
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);


        //14. 构造草稿数据。
        Map<String, Object> data = new HashMap<>();
        data.put("venueName", targetVenue.getVenueName());
        data.put("venueId", targetVenue.getId());
        data.put("courtName", targetCourt.getName());
        data.put("courtId", targetCourt.getId());
        data.put("courtType", targetCourt.getType());
        data.put("date", bookingDate);
        data.put("startTime", startTime);
        data.put("endTime", endTime);
        data.put("hoursPrice", targetCourt.getPrice());
        data.put("totalPrice", totalPrice);

        //15. 生成确认码。
        String confirmToken = AgentConfirmTokenUtil.generateToken();

        //16. 保存预约草稿到 Redis，按 userId + sessionId 隔离。
        //受保护写入：Redis 原子校验锁令牌，失锁后不得覆盖新状态，也不得宣称草稿已生成。
        com.sau.gym.admin.agent.store.SessionWriteResult writeResult = draftStore.saveIfHolder(
                userId,
                context.getSessionId(),
                new PendingDraft(
                        PendingDraftType.BOOKING,
                        data,
                        LocalDateTime.now(),
                        confirmToken
                ),
                context.getWriteTicket()
        );

        if (writeResult != com.sau.gym.admin.agent.store.SessionWriteResult.SAVED) {
            return AgentToolExecuteResult.failed(
                    writeResult == com.sau.gym.admin.agent.store.SessionWriteResult.LOCK_LOST
                            ? "会话状态已变更，本轮预约草稿未保存。请重新发起预约，不要重复确认。"
                            : "会话存储暂时不可用，预约草稿未保存。请稍后再试。",
                    "DRAFT_WRITE_" + writeResult.name()
            );
        }

        //17. 构造给用户看的草稿文本。
        String draftText =
                "已为您生成预约草稿：\n"
                        + "场馆：" + targetVenue.getVenueName() + "\n"
                        + "场地：" + targetCourt.getName() + "\n"
                        + "类型：" + targetCourt.getType() + "\n"
                        + "日期：" + bookingDate + "\n"
                        + "开始时间：" + startTime + "\n"
                        + "结束时间：" + endTime + "\n"
                        + "单小时价格：" + targetCourt.getPrice() + "\n"
                        + "总价：" + totalPrice + "\n"
                        + "确认码：" + confirmToken + "\n"
                        + "如果确认，请回复：确认预约 " + confirmToken + "\n"
                        + "如果放弃，请回复：取消\n"
                        + "注意：该草稿将在15分钟后自动过期。";

        //18. 返回统一工具结果。
        AgentToolExecuteResult result = AgentToolExecuteResult.needConfirm(
                draftText,
                data,
                AgentToolCodes.CONFIRM_BOOKING
        );


        //19. 补充扩展字段。
        result.addExtra("confirmToken", confirmToken);
        result.addExtra("venueId", targetVenue.getId());
        result.addExtra("courtId", targetCourt.getId());
        result.addExtra("totalPrice", totalPrice);

        return result;
    }
}

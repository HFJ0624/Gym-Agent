package com.sau.gym.admin.agent.tool;

import com.alibaba.fastjson.JSON;
import com.sau.gym.admin.agent.service.AgentToolGuardService;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.PendingDraft;
import com.sau.gym.admin.agent.tool.executor.AgentToolContextFactory;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import com.sau.gym.admin.enums.PendingDraftType;
import com.sau.gym.admin.agent.util.AgentToolLogHelper;
import com.sau.gym.admin.enums.AgentRiskLevel;
import com.sau.gym.admin.mapper.CourtBookingMapper;
import com.sau.gym.admin.mapper.CourtMapper;
import com.sau.gym.admin.mapper.VenueMapper;
import com.sau.gym.admin.service.CourtBookingService;
import com.sau.gym.model.dto.venue.BookingDto;
import com.sau.gym.model.entity.venue.Venue;
import com.sau.gym.model.vo.court.CourtBookVO;
import com.sau.gym.model.vo.court.CourtVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.sau.gym.admin.agent.util.AgentConfirmTokenUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:预约类工具
 * 负责两件事：
 * 1. 生成预约草稿（给大模型调用）
 * 2. 确认预约（给 Java 主流程调用，不给模型直接调用）
 * 日期: 2026/4/23 14:46
 */
@Component
public class GymBookingTools {

    /**
     * 工具注册器。
     *
     * 用于根据 toolCode 找到对应 Executor 并执行。
     */
    private final GymAgentToolRegistry gymAgentToolRegistry;

    /**
     * 工具上下文构建器。
     *
     * 用于构造 AgentToolExecuteContext。
     */
    private final AgentToolContextFactory agentToolContextFactory;

    /**
     * 预约业务 Service。
     *
     * 当前仍然用于 confirmPendingBooking。
     */
    private final CourtBookingService courtBookingService;

    /**
     * 草稿缓存。
     *
     * 当前仍然用于 confirmPendingBooking 读取和清理草稿。
     */
    private final AgentDraftStore draftStore;

    /**
     * 原有工具日志帮助类。
     *
     * 这里保留是为了不影响你原来的工具日志记录。
     */
    private final AgentToolLogHelper agentToolLogHelper;

    public GymBookingTools(
            GymAgentToolRegistry gymAgentToolRegistry,
            AgentToolContextFactory agentToolContextFactory,
            CourtBookingService courtBookingService,
            AgentDraftStore draftStore,
            AgentToolLogHelper agentToolLogHelper
    ) {
        this.gymAgentToolRegistry = gymAgentToolRegistry;
        this.agentToolContextFactory = agentToolContextFactory;
        this.courtBookingService = courtBookingService;
        this.draftStore = draftStore;
        this.agentToolLogHelper = agentToolLogHelper;
    }

    /**
     * 根据当前页面的场馆ID和场地ID生成预约草稿。
     * 这个方法仍然保留 @Tool 注解，给 LangChain4j 调用。
     * 但是:
     * 具体业务逻辑不再写在 GymBookingTools 里，
     * 而是交给 CreateBookingDraftToolExecutor。
     */
    @Tool("根据当前页面的场馆ID和场地ID生成预约草稿。当用户说“预约这个场地”“帮我预约当前场地”“这个场地明晚7点到8点”时优先使用这个工具。不会真正预约，也不会扣费。")
    public String createBookingDraftByContext(
            @P("当前页面场馆ID，来自页面上下文") Long venueId,
            @P("当前页面场地ID，来自页面上下文") Long courtId,
            @P("预约日期，格式必须是 yyyy-MM-dd，例如 2026-05-08") String date,
            @P("开始时间，格式必须是 HH:mm:ss，例如 19:00:00") String startTime,
            @P("结束时间，格式必须是 HH:mm:ss，例如 20:00:00") String endTime,
            @ToolMemoryId Long userId
    ) {
        long begin = System.currentTimeMillis();

        //1. 记录工具入参。
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("venueId", venueId);
        args.put("courtId", courtId);
        args.put("date", date);
        args.put("startTime", startTime);
        args.put("endTime", endTime);
        args.put("userId", userId);

        String toolName = "createBookingDraftByContext";
        String toolDesc = "根据当前页面的场馆ID和场地ID生成预约草稿";
        String toolClass = this.getClass().getName();
        String methodName = "createBookingDraftByContext";

        try {

            //2. 构造统一工具上下文。
            AgentToolExecuteContext context =
                    agentToolContextFactory.createBookingDraftContext(
                            "根据当前页面生成预约草稿",
                            venueId,
                            courtId,
                            date,
                            startTime,
                            endTime
                    );

            //3. 设置 userId。
            context.setUserId(userId);

            //4. 通过注册器执行预约草稿工具。
            AgentToolExecuteResult result = gymAgentToolRegistry.execute(
                    AgentToolCodes.CREATE_BOOKING_DRAFT,
                    context
            );

            //5. 返回 JSON 给大模型。
            String resultJson = JSON.toJSONString(result);

            //6. 记录工具调用成功日志。
            agentToolLogHelper.success(
                    toolName,
                    toolDesc,
                    toolClass,
                    methodName,
                    args,
                    resultJson,
                    System.currentTimeMillis() - begin
            );

            return resultJson;

        } catch (Exception e) {
            //7. 记录工具调用失败日志。
            agentToolLogHelper.fail(
                    toolName,
                    toolDesc,
                    toolClass,
                    methodName,
                    args,
                    e,
                    System.currentTimeMillis() - begin
            );

            throw e;
        }
    }

    /***
     *
     * @param userId 用户id
     * @param sessionId 会话id
     * @param confirmToken 确认码
     * @return 真正确认预约
     */
    public String confirmPendingBooking(Long userId, String sessionId, String confirmToken) {
        PendingDraft draft = draftStore.get(userId, sessionId);
        if (draft == null || draft.type() != PendingDraftType.BOOKING) {
            return "当前没有待确认的预约草稿。";
        }

        //新增校验确认码
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

            // 构建预约类
            BookingDto bookingDto = new BookingDto();
            bookingDto.setCourtId(courtId);
            bookingDto.setUserId(userId);
            bookingDto.setBookingDate(LocalDate.parse(date));
            bookingDto.setStartTime(LocalTime.parse(startTime));
            bookingDto.setEndTime(LocalTime.parse(endTime));
            bookingDto.setHoursPrice((BigDecimal) data.get("hoursPrice"));

            bookingDto.setRemark("LangChain4j预约-" + venueName + "-" + courtName);

            // 调用预约业务
            courtBookingService.saveCourtBook(bookingDto);

            // 成功后清除草稿
            draftStore.clear(userId, sessionId);

            return "预约成功：\n"
                    + "场馆：" + venueName + "\n"
                    + "场地：" + courtName + "\n"
                    + "日期：" + date + "\n"
                    + "开始时间：" + startTime + "\n"
                    + "结束时间：" + endTime + "\n"
                    + "已调用系统原有预约逻辑完成下单。";
        } catch (Exception e) {
            return "预约失败：" + e.getMessage();
        }
    }
}
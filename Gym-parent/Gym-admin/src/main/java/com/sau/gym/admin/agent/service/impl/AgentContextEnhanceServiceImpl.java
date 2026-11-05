package com.sau.gym.admin.agent.service.impl;

import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.admin.agent.model.AgentEntityResolveResult;
import com.sau.gym.admin.agent.model.BookingTimeInfo;
import com.sau.gym.admin.agent.parser.BookingTimeParser;
import com.sau.gym.admin.agent.service.AgentContextEnhanceService;
import com.sau.gym.admin.agent.service.AgentEntityResolveService;
import com.sau.gym.admin.agent.store.AgentBusinessContextStore;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.PendingDraft;
import com.sau.gym.admin.agent.util.AgentSessionIdUtil;
import com.sau.gym.admin.enums.PendingDraftType;
import com.sau.gym.model.dto.agent.AgentChatDto;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
/**
 * 作者:hfj
 * 功能:Agent多轮业务上下文增强服务实现类
 * 日期: 2026/5/6 20:12
 */
@Service
public class AgentContextEnhanceServiceImpl implements AgentContextEnhanceService {

    private final AgentBusinessContextStore contextStore;
    private final BookingTimeParser bookingTimeParser;
    private final AgentDraftStore agentDraftStore;

    private final AgentEntityResolveService agentEntityResolveService;

    public AgentContextEnhanceServiceImpl(AgentBusinessContextStore contextStore,
                                          BookingTimeParser bookingTimeParser,
                                          AgentDraftStore agentDraftStore,
                                          AgentEntityResolveService agentEntityResolveService
                                          ) {
        this.contextStore = contextStore;
        this.bookingTimeParser = bookingTimeParser;
        this.agentDraftStore = agentDraftStore;
        this.agentEntityResolveService = agentEntityResolveService;
    }

    /**
     * 在调用 Agent 前准备上下文。
     *
     * 处理逻辑：
     * 1. 先从 Redis 获取历史业务上下文
     * 2. 如果没有，则创建新的上下文
     * 3. 合并当前页面传来的 venueId / courtId
     * 4. 尝试从用户消息中解析日期和时间段
     * 5. 记录最近意图
     * 6. 保存回 Redis
     */
    @Override
    public AgentBusinessContext prepareBeforeAgent(Long userId, String sessionId, String message, AgentChatDto dto) {
        if (userId == null) {
            return null;
        }

        //按 userId + sessionId 读取，避免不同会话共用同一份槽位。
        AgentBusinessContext context = contextStore.get(userId, sessionId);

        // Redis 中没有上下文时，创建一个新的上下文对象
        if (context == null) {
            context = new AgentBusinessContext();
            context.setUserId(userId);
        }

        context.setSessionId(AgentSessionIdUtil.normalize(sessionId));

        // 记录最近一次用户输入，方便后续排查上下文来源
        context.setLastUserMessage(message);
        context.setUpdatedAt(LocalDateTime.now());

        //场馆/场地别名解析。
        AgentEntityResolveResult resolveResult = agentEntityResolveService.resolve(message);
        if (resolveResult != null && resolveResult.isResolved() && !resolveResult.isAmbiguous()) {
            if (resolveResult.getVenueId() != null) {
                context.setLastVenueId(resolveResult.getVenueId());
                context.setLastVenueName(resolveResult.getVenueName());
            }

            if (resolveResult.getCourtId() != null) {
                context.setLastCourtId(resolveResult.getCourtId());
                context.setLastCourtName(resolveResult.getCourtName());
                context.setLastCourtType(resolveResult.getCourtType());
            }
        }

        //合并前端页面上下文
        if (dto != null) {
            if (dto.getVenueId() != null) {
                context.setLastVenueId(dto.getVenueId());
            }

            if (dto.getCourtId() != null) {
                context.setLastCourtId(dto.getCourtId());
            }
        }

        //从用户消息中解析预约日期和时间段
        BookingTimeInfo timeInfo = bookingTimeParser.parse(message);

        if (timeInfo != null) {
            if (timeInfo.getDate() != null) {
                context.setLastBookingDate(timeInfo.getDate().toString());
            }

            if (timeInfo.getStartTime() != null) {
                context.setLastStartTime(timeInfo.getStartTime().toString());
            }

            if (timeInfo.getEndTime() != null) {
                context.setLastEndTime(timeInfo.getEndTime().toString());
            }
        }

        //粗粒度记录最近业务意图
        //这里不是最终的意图识别，只是为了辅助上下文。
        context.setLastIntent(guessIntent(message));

        // 保存上下文到 Redis
        contextStore.save(context);

        return context;
    }

    /**
     * 在 Agent 调用后刷新上下文。
     *
     * 主要用于把“预约草稿”里的准确信息写回 Redis。
     *
     * 例如：
     * 用户说：帮我预约明天晚上7点到9点的篮球场
     * Agent 工具生成草稿后，草稿里已经有明确的：
     * - venueId
     * - venueName
     * - courtId
     * - courtName
     * - date
     * - startTime
     * - endTime
     *
     * 这些信息比用户原始文本更准确，所以要反写到上下文里。
     */
    @Override
    public void refreshAfterAgent(Long userId, String sessionId) {
        if (userId == null) {
            return;
        }

        PendingDraft draft = agentDraftStore.get(userId, sessionId);

        if (draft == null) {
            return;
        }

        // 当前只处理预约草稿，商城草稿暂时不写入预约业务上下文
        if (draft.type() != PendingDraftType.BOOKING) {
            return;
        }

        AgentBusinessContext context = contextStore.get(userId, sessionId);

        if (context == null) {
            context = new AgentBusinessContext();
            context.setUserId(userId);
        }

        context.setSessionId(AgentSessionIdUtil.normalize(sessionId));

        Map<?, ?> data = draft.data();

        if (data == null || data.isEmpty()) {
            return;
        }

        //解析草稿的字段值
        Long venueId = toLong(data.get("venueId"));
        String venueName = toStringValue(data.get("venueName"));

        Long courtId = toLong(data.get("courtId"));
        String courtName = toStringValue(data.get("courtName"));
        String courtType = toStringValue(data.get("courtType"));

        String date = toStringValue(data.get("date"));
        String startTime = toStringValue(data.get("startTime"));
        String endTime = toStringValue(data.get("endTime"));

        //只有草稿中存在对应值时才覆盖上下文。避免草稿缺字段时，把原来 Redis 中有效的上下文覆盖成 null。
        if (venueId != null) {
            context.setLastVenueId(venueId);
        }

        if (notBlank(venueName)) {
            context.setLastVenueName(venueName);
        }

        if (courtId != null) {
            context.setLastCourtId(courtId);
        }

        if (notBlank(courtName)) {
            context.setLastCourtName(courtName);
        }

        if (notBlank(courtType)) {
            context.setLastCourtType(courtType);
        }

        if (notBlank(date)) {
            context.setLastBookingDate(date);
        }

        if (notBlank(startTime)) {
            context.setLastStartTime(startTime);
        }

        if (notBlank(endTime)) {
            context.setLastEndTime(endTime);
        }

        context.setLastIntent("BOOKING_CREATE");
        context.setUpdatedAt(LocalDateTime.now());

        contextStore.save(context);
    }

    /**
     * 构造业务上下文提示词。
     *
     * 这段内容会拼到用户消息前面传给大模型。
     *
     * 作用：
     * 当用户说“这个场馆”“这个场地”“刚才那个”时，
     * 大模型可以结合这里的结构化上下文理解用户指代。
     *
     * 注意：
     * 上下文只是辅助理解，不代表最终业务结果。
     * 最终预约是否成功，仍然必须走工具和数据库校验。
     */
    @Override
    public String buildContextPrompt(AgentBusinessContext context) {
        if (context == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        builder.append("【业务上下文】\n");

        if (context.getLastVenueId() != null) {
            builder.append("最近场馆ID：").append(context.getLastVenueId()).append("\n");
        }

        if (notBlank(context.getLastVenueName())) {
            builder.append("最近场馆名称：").append(context.getLastVenueName()).append("\n");
        }

        if (context.getLastCourtId() != null) {
            builder.append("最近场地ID：").append(context.getLastCourtId()).append("\n");
        }

        if (notBlank(context.getLastCourtName())) {
            builder.append("最近场地名称：").append(context.getLastCourtName()).append("\n");
        }

        if (notBlank(context.getLastCourtType())) {
            builder.append("最近场地类型：").append(context.getLastCourtType()).append("\n");
        }

        if (notBlank(context.getLastBookingDate())) {
            builder.append("最近预约日期：").append(context.getLastBookingDate()).append("\n");
        }

        if (notBlank(context.getLastStartTime())) {
            builder.append("最近开始时间：").append(context.getLastStartTime()).append("\n");
        }

        if (notBlank(context.getLastEndTime())) {
            builder.append("最近结束时间：").append(context.getLastEndTime()).append("\n");
        }

        if (notBlank(context.getLastIntent())) {
            builder.append("最近业务意图：").append(context.getLastIntent()).append("\n");
        }

        builder.append("如果用户说“这个场馆”“这个场地”“这里”“刚才那个”，")
                .append("优先结合上述业务上下文理解。\n");

        builder.append("如果用户输入中出现了场馆简称、场地简称或口语化名称，")
                .append("系统会尝试解析为标准场馆ID和场地ID。\n");

        builder.append("如果存在多个可能场地，不要直接替用户选择，")
                .append("应追问用户具体选择哪个场地。\n");

        builder.append("如果上下文仍然不足以完成预约，不要编造信息，应继续追问用户。\n");

        return builder.toString();
    }

    /**
     * 获取有效场馆ID。
     *
     * 优先级：
     * 1. 本次请求 dto.venueId
     * 2. Redis 上下文 lastVenueId
     */
    @Override
    public Long getEffectiveVenueId(AgentChatDto dto, AgentBusinessContext context) {
        if (dto != null && dto.getVenueId() != null) {
            return dto.getVenueId();
        }

        if (context != null) {
            return context.getLastVenueId();
        }

        return null;
    }

    /**
     * 获取有效场地ID。
     *
     * 优先级：
     * 1. 本次请求 dto.courtId
     * 2. Redis 上下文 lastCourtId
     */
    @Override
    public Long getEffectiveCourtId(AgentChatDto dto, AgentBusinessContext context) {
        if (dto != null && dto.getCourtId() != null) {
            return dto.getCourtId();
        }

        if (context != null) {
            return context.getLastCourtId();
        }

        return null;
    }

    /**
     * 简单判断最近业务意图。
     *
     * 这里不做复杂 NLP，只做关键词兜底。
     * 真正业务执行仍然以 Agent 工具和后端 Service 为准。
     */
    private String guessIntent(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "UNKNOWN";
        }

        String text = message.trim();

        if (text.contains("预约")
                || text.contains("预定")
                || text.contains("预订")
                || text.contains("帮我约")
                || text.contains("订场")) {
            return "BOOKING_CREATE";
        }

        if (text.contains("我的预约")
                || text.contains("我预约")
                || text.contains("我订了")
                || text.contains("我约了")) {
            return "BOOKING_QUERY";
        }

        if (text.contains("订单")
                || text.contains("我的订单")) {
            return "ORDER_QUERY";
        }

        if (text.contains("公告")
                || text.contains("通知")) {
            return "NOTICE_QUERY";
        }

        if (text.contains("规则")
                || text.contains("退款")
                || text.contains("取消")
                || text.contains("开放时间")
                || text.contains("怎么预约")
                || text.contains("怎么取消")) {
            return "RAG_QA";
        }

        if (text.contains("场馆")
                || text.contains("场地")
                || text.contains("篮球场")
                || text.contains("羽毛球场")
                || text.contains("足球场")) {
            return "VENUE_QUERY";
        }

        return "CHAT";
    }

    /**
     * 判断字符串是否非空。
     */
    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * Object 转 String。
     *
     * 草稿数据通常是 Map，里面的值可能是 Long、BigDecimal、LocalDate、LocalTime、String。
     * 所以这里统一转成 String。
     */
    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Object 转 Long。
     *
     * 草稿里的 venueId / courtId 可能是 Long，也可能被 JSON 反序列化成 Integer。
     * 所以这里统一用 String.valueOf 再转 Long。
     */
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

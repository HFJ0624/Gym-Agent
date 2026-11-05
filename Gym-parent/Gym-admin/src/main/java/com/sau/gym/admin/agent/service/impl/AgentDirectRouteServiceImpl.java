package com.sau.gym.admin.agent.service.impl;

import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.admin.agent.model.BookingTimeInfo;
import com.sau.gym.admin.agent.parser.BookingTimeParser;
import com.sau.gym.admin.agent.service.AgentDirectRouteService;
import com.sau.gym.admin.agent.tool.AgentToolCodes;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import com.sau.gym.admin.agent.trace.AgentTraceContext;
import org.springframework.stereotype.Service;

/**
 * 作者:hfj
 * 功能:Agent 直达路由服务实现
 * 说明:
 * 识别非常明确的当前场地预约请求，直接通过 Registry 执行预约草稿工具，
 * 不经过大模型，也不经过旧的 @Tool 包装层。
 */
@Service
public class AgentDirectRouteServiceImpl implements AgentDirectRouteService {

    /**
     * 咨询类关键词。
     * 出现这些词时说明用户在问规则或流程，不能被“预约”两个字误带入办理流程。
     */
    private static final String[] CONSULTATION_KEYWORDS = {
            "规则", "怎么", "如何", "可以吗", "能不能", "是否", "多少钱", "价格",
            "退款", "取消", "开放时间", "停车", "流程", "咨询"
    };

    private final BookingTimeParser bookingTimeParser;

    private final GymAgentToolRegistry registry;

    public AgentDirectRouteServiceImpl(BookingTimeParser bookingTimeParser,
                                       GymAgentToolRegistry registry) {
        this.bookingTimeParser = bookingTimeParser;
        this.registry = registry;
    }

    @Override
    public AgentToolExecuteResult tryHandle(Long userId,
                                            String sessionId,
                                            String message,
                                            Long venueId,
                                            Long courtId,
                                            AgentBusinessContext businessContext) {

        //没有预约意图，直接返回 null，让请求继续走 ReAct。
        if (!hasBookingIntent(message)) {
            return null;
        }

        //咨询类问题不走进办理流程。
        if (looksLikeConsultation(message)) {
            return null;
        }

        //直达路由只处理“当前场地预约”，必须有明确的场馆与场地。
        if (venueId == null || courtId == null) {
            return null;
        }

        //解析日期与时间段，解析失败交给 ReAct 追问或补充。
        BookingTimeInfo timeInfo = bookingTimeParser.parse(message);
        if (timeInfo == null || timeInfo.getDate() == null
                || timeInfo.getStartTime() == null || timeInfo.getEndTime() == null) {
            return null;
        }

        AgentToolExecuteContext context = new AgentToolExecuteContext();
        context.setUserId(userId);
        context.setSessionId(sessionId);
        context.setTraceId(AgentTraceContext.getTraceId());
        context.setRunId(AgentTraceContext.getTraceId());
        context.setOriginalQuestion(message);
        context.setBusinessContext(businessContext);

        context.addParam("venueId", venueId);
        context.addParam("courtId", courtId);
        context.addParam("bookingDate", timeInfo.getDate());
        context.addParam("startTime", timeInfo.getStartTime());
        context.addParam("endTime", timeInfo.getEndTime());

        return registry.execute(AgentToolCodes.CREATE_BOOKING_DRAFT, context);
    }

    /**
     * 判断用户输入是否有预约意图。
     */
    private boolean hasBookingIntent(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }

        String text = message.trim();

        return text.contains("预约")
                || text.contains("预定")
                || text.contains("预订")
                || text.contains("帮我约")
                || text.contains("帮我订")
                || text.contains("我要约")
                || text.contains("我要订");
    }

    /**
     * 判断是否属于咨询类问题。
     */
    private boolean looksLikeConsultation(String message) {
        if (message == null) {
            return false;
        }

        for (String keyword : CONSULTATION_KEYWORDS) {
            if (message.contains(keyword)) {
                return true;
            }
        }

        return false;
    }
}

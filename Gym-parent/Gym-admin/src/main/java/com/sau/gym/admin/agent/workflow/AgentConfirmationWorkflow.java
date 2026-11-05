package com.sau.gym.admin.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.service.AgentCancelBookingService;
import com.sau.gym.admin.agent.service.AgentToolLogService;
import com.sau.gym.admin.agent.service.AgentTraceService;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.tool.AgentToolCodes;
import com.sau.gym.admin.agent.tool.GymBookingTools;
import com.sau.gym.admin.agent.tool.GymShoppingTools;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import com.sau.gym.admin.agent.runtime.AgentRunResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:确认与取消流程
 * 说明:
 * 1. 确认动作优先于模型执行，必须读取当前会话草稿并校验确认码。
 * 2. 真实预约、真实下单、真实取消继续走原有业务 Service 与最终业务校验。
 * 3. “取消预约 12”只生成取消草稿，不直接取消。
 */
@Component
public class AgentConfirmationWorkflow {

    private final AgentDraftStore draftStore;

    private final GymBookingTools gymBookingTools;

    private final GymShoppingTools gymShoppingTools;

    private final AgentCancelBookingService agentCancelBookingService;

    private final GymAgentToolRegistry registry;

    private final AgentToolLogService agentToolLogService;

    private final AgentTraceService agentTraceService;

    private final ObjectMapper objectMapper;

    public AgentConfirmationWorkflow(AgentDraftStore draftStore,
                                     GymBookingTools gymBookingTools,
                                     GymShoppingTools gymShoppingTools,
                                     AgentCancelBookingService agentCancelBookingService,
                                     GymAgentToolRegistry registry,
                                     AgentToolLogService agentToolLogService,
                                     AgentTraceService agentTraceService,
                                     ObjectMapper objectMapper) {
        this.draftStore = draftStore;
        this.gymBookingTools = gymBookingTools;
        this.gymShoppingTools = gymShoppingTools;
        this.agentCancelBookingService = agentCancelBookingService;
        this.registry = registry;
        this.agentToolLogService = agentToolLogService;
        this.agentTraceService = agentTraceService;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理确认/取消指令。
     */
    public AgentRunResult handle(WorkflowDecision decision, Long userId, String sessionId, String message) {
        String command = String.valueOf(decision.getPayload().get("command"));
        String token = decision.getPayload().get("token") == null
                ? null
                : String.valueOf(decision.getPayload().get("token"));

        long start = System.currentTimeMillis();

        String reply;

        switch (command) {
            case "confirm_booking" -> {
                reply = requireLogin(userId, "请先登录后再确认预约。",
                        () -> gymBookingTools.confirmPendingBooking(userId, sessionId, token));
                recordLog("confirmPendingBooking", "用户确认预约草稿，执行真实预约业务",
                        message, reply, System.currentTimeMillis() - start);
            }

            case "confirm_shopping" -> {
                reply = requireLogin(userId, "请先登录后再确认下单。",
                        () -> gymShoppingTools.confirmPendingShopping(userId, sessionId, token));
                recordLog("confirmPendingShopping", "用户确认商品下单草稿，执行真实下单业务",
                        message, reply, System.currentTimeMillis() - start);
            }

            case "confirm_cancel_booking" -> {
                reply = requireLogin(userId, "请先登录后再确认取消预约。",
                        () -> agentCancelBookingService.confirmCancelBooking(userId, sessionId, token));
                recordLog("confirmCancelBooking", "用户确认取消预约草稿，执行真实取消预约业务",
                        message, reply, System.currentTimeMillis() - start);
            }

            case "clear_draft" -> {
                if (userId != null) {
                    draftStore.clear(userId, sessionId);
                }
                reply = "已取消当前待确认操作。";
                recordLog("clearPendingDraft", "用户取消当前待确认草稿", message, reply,
                        System.currentTimeMillis() - start);
            }

            default -> reply = "暂不支持该操作。";
        }

        agentTraceService.addStep(
                "PENDING_ACTION",
                "处理确认/取消指令: " + command,
                message,
                reply,
                "SUCCESS",
                null,
                System.currentTimeMillis() - start
        );

        return AgentRunResult.completed(reply);
    }

    /**
     * 处理“取消预约 12”，生成取消草稿并进入待确认状态。
     */
    public AgentRunResult handleCancelBookingDraft(String bookingIdText,
                                                   Long userId,
                                                   String sessionId,
                                                   String message) {
        if (userId == null) {
            return AgentRunResult.completed("请先登录后再取消预约。");
        }

        Long bookingId;

        try {
            bookingId = Long.valueOf(bookingIdText);
        } catch (Exception e) {
            return AgentRunResult.completed("预约ID格式不正确，请提供数字ID，例如：取消预约 12。");
        }

        AgentToolExecuteContext context = new AgentToolExecuteContext();
        context.setUserId(userId);
        context.setSessionId(sessionId);
        context.setOriginalQuestion(message);
        context.addParam("bookingId", bookingId);
        context.addParam("reason", "用户通过Agent取消预约");

        AgentToolExecuteResult result = registry.execute(AgentToolCodes.CREATE_CANCEL_BOOKING_DRAFT, context);

        agentTraceService.addStep(
                "DIRECT_CANCEL_DRAFT",
                "根据预约ID生成取消草稿",
                "bookingId=" + bookingId,
                result == null ? null : result.getMessage(),
                result != null && result.isSuccess() ? "SUCCESS" : "FAILED",
                result == null ? "empty result" : result.getErrorDetail(),
                result == null ? 0L : result.getCostTimeMs()
        );

        if (result == null) {
            return AgentRunResult.failed("生成取消预约草稿失败，请稍后重试。", "CANCEL_DRAFT_EMPTY");
        }

        if (result.isNeedConfirm()) {
            return AgentRunResult.waitingConfirmation(
                    result.getMessage(),
                    toJson(result),
                    result.getConfirmAction()
            );
        }

        //非待确认结果（例如参数错误、权限不足）直接作为最终文本返回。
        return AgentRunResult.completed(String.valueOf(result.getMessage()));
    }

    /**
     * 未登录时直接返回提示，不调用业务写操作。
     */
    private String requireLogin(Long userId, String guestMessage, java.util.function.Supplier<String> action) {
        if (userId == null) {
            return guestMessage;
        }

        try {
            return action.get();
        } catch (Exception e) {
            //业务异常不把堆栈暴露给用户。
            return "操作未完成，请稍后重试或到订单页面处理。";
        }
    }

    /**
     * 记录工具日志。
     * 这里的 SUCCESS/FAILED 只用于后台展示，真实业务状态以业务返回文本为准。
     */
    private void recordLog(String toolName,
                           String toolDesc,
                           String message,
                           String reply,
                           long costMs) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("message", message);

            agentToolLogService.record(
                    toolName,
                    toolDesc,
                    this.getClass().getName(),
                    toolName,
                    objectMapper.writeValueAsString(args),
                    reply,
                    looksSuccessful(reply) ? "SUCCESS" : "FAILED",
                    null,
                    costMs
            );
        } catch (Exception e) {
            //日志失败不影响用户操作。
            agentTraceService.addStep("TOOL_LOG", "记录工具日志失败", message,
                    null, "FAILED", e.getMessage(), 0L);
        }
    }

    /**
     * 仅用于日志分类的粗略判断。
     */
    private boolean looksSuccessful(String reply) {
        if (!StringUtils.hasText(reply)) {
            return false;
        }

        return reply.contains("成功") || reply.contains("已取消当前待确认操作");
    }

    /**
     * 序列化工具结果，失败时返回 null。
     */
    private String toJson(AgentToolExecuteResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return null;
        }
    }
}

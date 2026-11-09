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
     * 说明：本方法执行结束只代表流程处理完成；
     * 业务办理是否成功由各分支的真实业务结果决定
     * （商品确认按 OrderConfirmationStatus 标注 businessFailure/errorCode）。
     */
    public AgentRunResult handle(WorkflowDecision decision, Long userId, String sessionId, String message,
                                 com.sau.gym.admin.agent.store.SessionWriteTicket ticket) {
        String command = String.valueOf(decision.getPayload().get("command"));
        String token = decision.getPayload().get("token") == null
                ? null
                : String.valueOf(decision.getPayload().get("token"));

        long start = System.currentTimeMillis();

        String reply;
        com.sau.gym.model.vo.order.OrderConfirmationResult shoppingResult = null;

        switch (command) {
            case "confirm_booking" -> {
                reply = requireLogin(userId, "请先登录后再确认预约。",
                        () -> gymBookingTools.confirmPendingBooking(userId, sessionId, token, ticket));
                recordLog("confirmPendingBooking", "用户确认预约草稿，执行真实预约业务",
                        message, reply, System.currentTimeMillis() - start);
            }

            case "confirm_shopping" -> {
                //未登录时返回本请求自己的 INVALID_DRAFT 结果，不共享任何跨请求状态。
                shoppingResult = requireLoginResult(userId,
                        invalidShoppingResult("请先登录后再确认下单。"),
                        () -> gymShoppingTools.confirmPendingShopping(userId, sessionId, token, ticket));

                reply = shoppingResult.getMessage();

                recordLog("confirmPendingShopping",
                        "用户确认商品下单草稿，业务状态=" + shoppingResult.getStatus(),
                        message, reply, System.currentTimeMillis() - start);
            }

            case "confirm_cancel_booking" -> {
                reply = requireLogin(userId, "请先登录后再确认取消预约。",
                        () -> agentCancelBookingService.confirmCancelBooking(userId, sessionId, token, ticket));
                recordLog("confirmCancelBooking", "用户确认取消预约草稿，执行真实取消预约业务",
                        message, reply, System.currentTimeMillis() - start);
            }

            case "clear_draft" -> {
                if (userId != null) {
                    //受保护清理：旧请求失锁后不得清除新持有者的草稿。
                    com.sau.gym.admin.agent.store.SessionWriteResult clearResult =
                            draftStore.clearIfHolder(userId, sessionId, ticket);
                    if (clearResult != com.sau.gym.admin.agent.store.SessionWriteResult.SAVED) {
                        reply = "会话状态已变更，当前草稿未清理。";
                        recordLog("clearPendingDraft", "清理草稿被条件写拒绝", message, reply,
                                System.currentTimeMillis() - start);
                        break;
                    }
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

        AgentRunResult runResult = AgentRunResult.completed(reply);

        //按真实业务结果标注：办理失败不能被记成业务成功，业务标识供恢复查询关联订单。
        if (shoppingResult != null) {
            String status = shoppingResult.getStatus();

            boolean businessOk =
                    com.sau.gym.model.enums.order.OrderConfirmationStatus.PAID.name().equals(status)
                            || com.sau.gym.model.enums.order.OrderConfirmationStatus.PENDING_PAYMENT.name().equals(status);

            runResult.setBusinessFailure(!businessOk);
            runResult.setErrorCode(businessOk ? null : status);
            runResult.setBusinessId(shoppingResult.getConfirmationId());

            //已成交回执卡片（只展示真实订单字段，历史成交与待确认草稿区分）。
            if (businessOk && shoppingResult.getOrderId() != null) {
                java.util.Map<String, Object> receipt = new java.util.LinkedHashMap<>();
                receipt.put("type", "ORDER_RECEIPT");
                java.util.Map<String, Object> receiptData = new java.util.LinkedHashMap<>();
                receiptData.put("orderId", shoppingResult.getOrderId());
                receiptData.put("orderNo", shoppingResult.getOrderNo());
                receiptData.put("totalAmount", shoppingResult.getTotalAmount());
                receiptData.put("payStatus", shoppingResult.getPayStatus());
                receiptData.put("confirmationId", shoppingResult.getConfirmationId());
                receiptData.put("replay", shoppingResult.isReplay());
                receipt.put("data", receiptData);
                runResult.getCards().add(receipt);
            }
        }

        return runResult;
    }

    /**
     * 构造未登录时的商品确认结果。
     */
    private com.sau.gym.model.vo.order.OrderConfirmationResult invalidShoppingResult(String message) {
        com.sau.gym.model.vo.order.OrderConfirmationResult result =
                new com.sau.gym.model.vo.order.OrderConfirmationResult();
        result.setStatus(com.sau.gym.model.enums.order.OrderConfirmationStatus.INVALID_DRAFT.name());
        result.setErrorCode("INVALID_DRAFT");
        result.setMessage(message);
        return result;
    }

    /**
     * 处理“取消预约 12”：生成取消草稿并进入待确认状态。
     */
    public AgentRunResult handleCancelBookingDraft(String bookingIdText,
                                                   Long userId,
                                                   String sessionId,
                                                   String message,
                                                   com.sau.gym.admin.agent.store.SessionWriteTicket ticket) {
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
        context.setWriteTicket(ticket);
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
     * 未登录时直接返回提示，不调用业务写操作（文本分支）。
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
     * 未登录时直接返回失败结果，不调用业务写操作（结构化结果分支）。
     */
    private com.sau.gym.model.vo.order.OrderConfirmationResult requireLoginResult(
            Long userId,
            com.sau.gym.model.vo.order.OrderConfirmationResult guestValue,
            java.util.function.Supplier<com.sau.gym.model.vo.order.OrderConfirmationResult> action) {
        if (userId == null) {
            return guestValue;
        }

        try {
            return action.get();
        } catch (Exception e) {
            //业务异常不把堆栈暴露给用户；结果待定，由回执查询收敛。
            return invalidShoppingResult("操作未完成，结果待核实。请稍后通过确认记录查询，不要盲目重复确认。");
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

package com.sau.gym.admin.agent.tool;

import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.PendingDraft;
import com.sau.gym.admin.enums.PendingDraftType;
import com.sau.gym.admin.agent.util.AgentConfirmTokenUtil;
import com.sau.gym.admin.service.OrderService;
import com.sau.gym.model.dto.order.ShoppingConfirmationCommand;
import com.sau.gym.model.enums.order.OrderConfirmationStatus;
import com.sau.gym.model.vo.order.OrderConfirmationResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 作者:hfj
 * 功能:商品确认工具
 * 说明:
 * 1. 草稿生成已迁移到 CreateShoppingDraftToolExecutor（自建 Function Calling 链路），
 *    旧的 LangChain4j @Tool 包装方法已随 GymRagTools 等一并删除。
 * 2. 本类只保留确认办理方法，由 AgentConfirmationWorkflow 在用户确认时调用，
 *    不向模型开放；内部直接进入共享交易核心 ShoppingOrderTxService，
 *    不再插入临时购物车。
 * 3. 结果只来自真实业务执行：各分支（无草稿、校验失败、办理成功/失败）都返回
 *    当前请求自己的结构化结果，不依赖任何共享可变状态。
 * 日期: 2026/4/23 14:47
 */
@Component
public class GymShoppingTools {

    /**
     * 订单服务门面：委托事务核心 ShoppingOrderTxService 并做结果映射。
     */
    private final OrderService orderService;

    /**
     * 草稿缓存：读取当前草稿、受控补齐 confirmationId、成功后受保护清理。
     */
    private final AgentDraftStore draftStore;

    public GymShoppingTools(OrderService orderService, AgentDraftStore draftStore) {
        this.orderService = orderService;
        this.draftStore = draftStore;
    }

    /**
     * 确认商品下单草稿并执行真实下单。
     *
     * @param userId       可信用户ID（来自 Workflow，不来自模型）
     * @param sessionId    会话ID
     * @param confirmToken 用户输入的确认码；必须与当前草稿的确认码一致，
     *                     不匹配时拒绝执行且不产生任何业务写入
     * @param ticket       会话受保护写入凭证（来自真实锁句柄），用于：
     *                     补齐旧草稿的 confirmationId、生成新价格草稿、成功后清理草稿；
     *                     条件写被拒（LOCK_LOST/STORE_UNAVAILABLE）时相关草稿操作不执行
     * @return 本次请求自己的结构化结果：
     *         无草稿/类型不符/确认码错误 → INVALID_DRAFT（无业务写入）；
     *         PAID/PENDING_PAYMENT → 事务已提交的真实回执，随后尝试受保护清理草稿，
     *         清理被拒时在文案中说明重复确认会由回执收敛；
     *         PRICE_CHANGED → 携带最新快照并已生成新价格草稿等待再次确认；
     *         OUT_OF_STOCK/BUSINESS_FAILED → 办理未成功，草稿保留供调整重试；
     *         RESULT_UNKNOWN → 结果待核实，不清理草稿、不盲目重试
     */
    public OrderConfirmationResult confirmPendingShopping(Long userId, String sessionId, String confirmToken,
                                                          com.sau.gym.admin.agent.store.SessionWriteTicket ticket) {
        PendingDraft draft = draftStore.get(userId, sessionId);

        if (draft == null || draft.type() != PendingDraftType.SHOPPING) {
            return invalidDraft("当前没有待确认的商品下单草稿，可能已经确认、取消或过期。");
        }

        if (confirmToken == null || confirmToken.trim().isEmpty()) {
            return invalidDraft("请带上确认码，例如：确认下单 " + draft.confirmToken());
        }

        if (!draft.confirmToken().equals(confirmToken.trim())) {
            return invalidDraft("确认码错误，请核对后重新输入。正确格式为：确认下单 " + draft.confirmToken());
        }

        Map<String, Object> data = draft.data();

        //1. 确定业务确认标识：新草稿自带；旧有效草稿在持有会话执行权时受控补齐一次。
        String confirmationId = data.get("confirmationId") == null
                ? null
                : String.valueOf(data.get("confirmationId"));

        if (confirmationId == null || confirmationId.isBlank()) {
            confirmationId = UUID.randomUUID().toString().replace("-", "");

            Map<String, Object> patched = new java.util.LinkedHashMap<>(data);
            patched.put("confirmationId", confirmationId);

            com.sau.gym.admin.agent.store.SessionWriteResult patchResult = draftStore.saveIfHolder(
                    userId, sessionId,
                    new PendingDraft(draft.type(), patched, draft.createdAt(), draft.confirmToken()),
                    ticket
            );

            if (patchResult != com.sau.gym.admin.agent.store.SessionWriteResult.SAVED) {
                return invalidDraft("会话状态已变更或存储暂不可用，本次确认未执行。请重新查看草稿后再确认。");
            }
        }

        //2. 进入共享交易核心：参数全部来自服务端草稿快照与可信身份。
        ShoppingConfirmationCommand command = new ShoppingConfirmationCommand();
        command.setUserId(userId);
        command.setSessionId(sessionId);
        command.setConfirmationId(confirmationId);
        command.setGoodsId(((Number) data.get("goodsId")).longValue());
        command.setGoodsName(String.valueOf(data.get("goodsName")));
        command.setQuantity(((Number) data.get("quantity")).intValue());
        command.setPriceSnapshot(data.get("price") == null
                ? java.math.BigDecimal.ZERO
                : new java.math.BigDecimal(String.valueOf(data.get("price"))));

        OrderConfirmationResult result = orderService.confirmShopping(command);

        //3. 按真实业务状态处理草稿；不伪造成功。
        String status = result.getStatus();

        if (OrderConfirmationStatus.PAID.name().equals(status)
                || OrderConfirmationStatus.PENDING_PAYMENT.name().equals(status)) {
            //数据库已提交：受保护清理本次草稿；清理失败不影响回执，重复确认由回执收敛。
            com.sau.gym.admin.agent.store.SessionWriteResult clearResult =
                    draftStore.clearIfHolder(userId, sessionId, ticket);

            if (clearResult != com.sau.gym.admin.agent.store.SessionWriteResult.SAVED) {
                result.setMessage(result.getMessage()
                        + "\n（提示：本地草稿清理未获授权，重复确认会自动定位本订单，不会重复扣款。）");
            }

            return result;
        }

        if (OrderConfirmationStatus.PRICE_CHANGED.name().equals(status)
                && result.getLatestSnapshot() != null) {
            //价格变化：生成新价格草稿等待再次明确确认；本次扣款为零。
            Map<String, Object> newDraftData = new java.util.LinkedHashMap<>(result.getLatestSnapshot());
            String newConfirmationId = UUID.randomUUID().toString().replace("-", "");
            newDraftData.put("confirmationId", newConfirmationId);

            String newToken = AgentConfirmTokenUtil.generateToken();

            draftStore.saveIfHolder(userId, sessionId,
                    new PendingDraft(PendingDraftType.SHOPPING, newDraftData,
                            LocalDateTime.now(), newToken),
                    ticket);

            result.setMessage(result.getMessage()
                    + "\n新草稿确认码：" + newToken
                    + "\n如确认按新价格下单，请回复：确认下单 " + newToken);

            return result;
        }

        //RESULT_UNKNOWN / OUT_OF_STOCK / BUSINESS_FAILED：如实返回；草稿保留（结果待定由回执收敛，失败可调整重试）。
        return result;
    }

    /**
     * 构造 INVALID_DRAFT 结果。
     */
    private OrderConfirmationResult invalidDraft(String message) {
        OrderConfirmationResult result = new OrderConfirmationResult();
        result.setStatus(OrderConfirmationStatus.INVALID_DRAFT.name());
        result.setErrorCode("INVALID_DRAFT");
        result.setMessage(message);
        return result;
    }
}

package com.sau.gym.admin.agent.tool;

import com.alibaba.fastjson.JSON;
import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.PendingDraft;
import com.sau.gym.admin.agent.tool.executor.AgentToolContextFactory;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import com.sau.gym.admin.enums.PendingDraftType;
import com.sau.gym.admin.agent.util.AgentConfirmTokenUtil;
import com.sau.gym.admin.mapper.BeverageMapper;
import com.sau.gym.admin.mapper.CartMapper;
import com.sau.gym.admin.mapper.UserMapper;
import com.sau.gym.admin.service.OrderService;
import com.sau.gym.model.dto.order.OrderDto;
import com.sau.gym.model.entity.shopping.Beverage;
import com.sau.gym.model.entity.shopping.Cart;
import com.sau.gym.model.entity.user.User;
import com.sau.gym.utils.AuthContextUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
/**
 * 作者:hfj
 * 功能:商城工具类
 * 负责：
 * 1. 生成商品下单草稿
 * 2. 用户确认后真正下单
 * 日期: 2026/4/23 14:47
 */ 
@Component
public class GymShoppingTools {

    private final BeverageMapper beverageMapper;
    private final CartMapper cartMapper;
    private final UserMapper userMapper;
    private final OrderService orderService;
    private final AgentDraftStore draftStore;

    private final GymAgentToolRegistry gymAgentToolRegistry;

    private final AgentToolContextFactory agentToolContextFactory;

    /**
     * 最近一次商品确认的结构化结果（请求线程内有效，供 Workflow 读取）。
     */
    private com.sau.gym.model.vo.order.OrderConfirmationResult lastConfirmationResult;

    public GymShoppingTools(BeverageMapper beverageMapper,
                            CartMapper cartMapper,
                            UserMapper userMapper,
                            OrderService orderService,
                            AgentDraftStore draftStore,
                            GymAgentToolRegistry gymAgentToolRegistry,
                            AgentToolContextFactory agentToolContextFactory) {
        this.beverageMapper = beverageMapper;
        this.cartMapper = cartMapper;
        this.userMapper = userMapper;
        this.orderService = orderService;
        this.draftStore = draftStore;
        this.gymAgentToolRegistry = gymAgentToolRegistry;
        this.agentToolContextFactory = agentToolContextFactory;
    }

    /***
     *
     * @param productName 商品名称
     * @param quantity 商品数量
     * @param userId 用户id
     * @return 创建商品下单草稿
     */
    @Tool("根据商品名称和数量生成商城下单草稿。不会真正下单，不会扣余额。")
    public String createShoppingDraft(
            @P("商品名称") String productName,
            @P("商品数量") Integer quantity,
            @ToolMemoryId Long userId
    ) {
        // 1. 构造统一工具执行上下文。
        AgentToolExecuteContext context = agentToolContextFactory.createShoppingDraftContext(
                "创建商品下单草稿",
                productName,
                quantity
        );

        // 2. 通过工具注册器执行工具。
        AgentToolExecuteResult result = gymAgentToolRegistry.execute(
                AgentToolCodes.CREATE_SHOPPING_DRAFT,
                context
        );

        // 3. 返回统一 JSON 给大模型。
        return JSON.toJSONString(result);
    }

    /***
     *
     * @param userId 用户id
     * @param sessionId 会话id
     * @return 真正执行商品下单
     */
    public String confirmPendingShopping(Long userId, String sessionId, String confirmToken,
                                         com.sau.gym.admin.agent.store.SessionWriteTicket ticket) {
        PendingDraft draft = draftStore.get(userId, sessionId);

        if (draft == null || draft.type() != PendingDraftType.SHOPPING) {
            return "当前没有待确认的商品下单草稿，可能已经确认、取消或过期。";
        }

        //校验确认码
        if (confirmToken == null || confirmToken.trim().isEmpty()) {
            return "请带上确认码，例如：确认下单 " + draft.confirmToken();
        }

        if (!draft.confirmToken().equals(confirmToken.trim())) {
            return "确认码错误，请核对后重新输入。正确格式为：确认下单 " + draft.confirmToken();
        }

        Map<String, Object> data = draft.data();

        //1. 确定业务确认标识：新草稿自带；旧有效草稿在持有会话执行权时受控补齐一次。
        String confirmationId = data.get("confirmationId") == null
                ? null
                : String.valueOf(data.get("confirmationId"));

        if (confirmationId == null || confirmationId.isBlank()) {
            confirmationId = java.util.UUID.randomUUID().toString().replace("-", "");

            Map<String, Object> patched = new java.util.LinkedHashMap<>(data);
            patched.put("confirmationId", confirmationId);

            com.sau.gym.admin.agent.store.SessionWriteResult patchResult = draftStore.saveIfHolder(
                    userId, sessionId,
                    new PendingDraft(draft.type(), patched, draft.createdAt(), draft.confirmToken()),
                    ticket
            );

            if (patchResult != com.sau.gym.admin.agent.store.SessionWriteResult.SAVED) {
                return "会话状态已变更或存储暂不可用，本次确认未执行。请重新查看草稿后再确认。";
            }
        }

        //2. 直接进入共享交易核心：不再插入临时购物车，参数全部来自服务端草稿快照与可信身份。
        com.sau.gym.model.dto.order.ShoppingConfirmationCommand command =
                new com.sau.gym.model.dto.order.ShoppingConfirmationCommand();
        command.setUserId(userId);
        command.setSessionId(sessionId);
        command.setConfirmationId(confirmationId);
        command.setGoodsId(((Number) data.get("goodsId")).longValue());
        command.setGoodsName(String.valueOf(data.get("goodsName")));
        command.setQuantity(((Number) data.get("quantity")).intValue());
        command.setPriceSnapshot(data.get("price") == null
                ? java.math.BigDecimal.ZERO
                : new java.math.BigDecimal(String.valueOf(data.get("price"))));

        com.sau.gym.model.vo.order.OrderConfirmationResult result = orderService.confirmShopping(command);
        this.lastConfirmationResult = result;

        //3. 按真实业务状态给出可读回复与草稿处理；不伪造成功。
        String status = result.getStatus();

        if (com.sau.gym.model.enums.order.OrderConfirmationStatus.PAID.name().equals(status)
                || com.sau.gym.model.enums.order.OrderConfirmationStatus.PENDING_PAYMENT.name().equals(status)) {
            //数据库已提交：受保护清理本次草稿；清理失败不影响回执，重复确认由回执收敛。
            com.sau.gym.admin.agent.store.SessionWriteResult clearResult =
                    draftStore.clearIfHolder(userId, sessionId, ticket);

            if (clearResult != com.sau.gym.admin.agent.store.SessionWriteResult.SAVED) {
                return result.getMessage()
                        + "\n（提示：本地草稿清理未获授权，重复确认会自动定位本订单，不会重复扣款。）";
            }

            return result.getMessage();
        }

        if (com.sau.gym.model.enums.order.OrderConfirmationStatus.PRICE_CHANGED.name().equals(status)
                && result.getLatestSnapshot() != null) {
            //价格变化：生成新价格草稿等待再次明确确认；本次扣款为零。
            Map<String, Object> newDraftData = new java.util.LinkedHashMap<>(result.getLatestSnapshot());
            String newConfirmationId = java.util.UUID.randomUUID().toString().replace("-", "");
            newDraftData.put("confirmationId", newConfirmationId);

            String newToken = AgentConfirmTokenUtil.generateToken();

            draftStore.saveIfHolder(userId, sessionId,
                    new PendingDraft(PendingDraftType.SHOPPING, newDraftData,
                            java.time.LocalDateTime.now(), newToken),
                    ticket);

            return result.getMessage()
                    + "\n新草稿确认码：" + newToken
                    + "\n如确认按新价格下单，请回复：确认下单 " + newToken;
        }

        //RESULT_UNKNOWN / OUT_OF_STOCK / BUSINESS_FAILED：如实返回；草稿保留（RESULT_UNKNOWN 由回执收敛，失败可调整重试）。
        return result.getMessage();
    }

    /**
     * 最近一次商品确认的结构化结果（Workflow 组装结构化回复用）。
     */
    public com.sau.gym.model.vo.order.OrderConfirmationResult getLastConfirmationResult() {
        return lastConfirmationResult;
    }
}

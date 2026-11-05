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
    public String confirmPendingShopping(Long userId, String sessionId, String confirmToken) {
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

        try {
            Map<String, Object> data = draft.data();

            Long goodsId = ((Number) data.get("goodsId")).longValue();
            String goodsName = String.valueOf(data.get("goodsName"));
            Integer quantity = ((Number) data.get("quantity")).intValue();

            Beverage beverage = beverageMapper.selectById(goodsId);
            if (beverage == null) {
                return "下单失败：商品不存在。";
            }
            if (beverage.getStock() < quantity) {
                return "下单失败：库存不足。";
            }

            Cart autoCart = new Cart();
            autoCart.setUserId(userId);
            autoCart.setGoodsId(beverage.getId());
            autoCart.setGoodsName(beverage.getGoodsName());
            autoCart.setPrice(beverage.getPrice());
            autoCart.setQuantity(quantity);
            autoCart.setImage(beverage.getImage());
            cartMapper.insert(autoCart);

            OrderDto orderDto = new OrderDto();
            orderDto.setCartIds(Collections.singletonList(autoCart.getId()));
            orderDto.setRemark("LangChain4j智能下单");

            // 设置当前用户上下文
            User user = userMapper.selectById(userId);
            AuthContextUtil.set(user);

            // 下单
            orderService.CreateShoppingOrder(orderDto);

            // 成功后清除草稿
            draftStore.clear(userId, sessionId);

            return "下单成功：\n"
                    + "商品：" + goodsName + "\n"
                    + "数量：" + quantity + "\n"
                    + "总价：" + beverage.getPrice().multiply(BigDecimal.valueOf(quantity)) + "\n"
                    + "已调用系统原有商城下单逻辑完成结算。";
        } catch (Exception e) {
            return "下单失败：" + e.getMessage();
        }
    }
}

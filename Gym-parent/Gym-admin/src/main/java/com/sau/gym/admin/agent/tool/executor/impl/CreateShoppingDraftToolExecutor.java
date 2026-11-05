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
import com.sau.gym.admin.mapper.BeverageMapper;
import com.sau.gym.model.entity.shopping.Beverage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:
 * 日期: 2026/5/27 20:49
 */
@Component
public class CreateShoppingDraftToolExecutor extends AbstractGymAgentToolExecutor {

    private final BeverageMapper beverageMapper;
    private final AgentDraftStore draftStore;

    public CreateShoppingDraftToolExecutor(
            AgentToolGuardService agentToolGuardService,
            BeverageMapper beverageMapper,
            AgentDraftStore draftStore
    ) {
        super(agentToolGuardService);
        this.beverageMapper = beverageMapper;
        this.draftStore = draftStore;
    }

    @Override
    public String toolCode() {
        return AgentToolCodes.CREATE_SHOPPING_DRAFT;
    }

    @Override
    public String toolName() {
        return "生成商品下单草稿";
    }

    @Override
    public String description() {
        return "根据商品名称和数量生成商城下单草稿。不会真正下单，用户确认后才会执行真实下单。";
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
        // 生成草稿本身不需要确认，真正下单时才确认。
        return false;
    }

    @Override
    public int rateLimitSeconds() {
        return 3;
    }

    @Override
    public List paramDefinitions() {
        return Arrays.asList(
                new AgentToolParamDefinition(
                        "productName",
                        "商品名称",
                        "String",
                        true,
                        "矿泉水"
                ),
                new AgentToolParamDefinition(
                        "quantity",
                        "商品数量",
                        "Integer",
                        false,
                        "2"
                )
        );
    }

    @Override
    protected AgentToolExecuteResult doExecute(AgentToolExecuteContext context) {
        Long userId = context.getUserId();
        String productName = context.getStringParam("productName");
        Integer quantity = context.getIntegerParam("quantity");

        if (!StringUtils.hasText(productName)) {
            return AgentToolExecuteResult.paramError(
                    "请提供商品名称。",
                    "productName is blank"
            );
        }

        // 默认购买数量为 1。
        if (quantity == null || quantity <= 0) {
            quantity = 1;
        }

        Beverage beverage = beverageMapper.selectByName(productName);

        if (beverage == null) {
            return AgentToolExecuteResult.failed(
                    "未找到商品：" + productName,
                    "Beverage not found: " + productName
            );
        }

        if (beverage.getStatus() != null && beverage.getStatus() == 2) {
            return AgentToolExecuteResult.failed(
                    "商品〖" + beverage.getGoodsName() + "〗已下架。",
                    "Beverage disabled"
            );
        }

        if (beverage.getStock() < quantity) {
            return AgentToolExecuteResult.failed(
                    "商品〖" + beverage.getGoodsName() + "〗库存不足，当前库存：" + beverage.getStock(),
                    "Stock not enough"
            );
        }

        Map<String, Object> data = new HashMap<>();
        data.put("goodsId", beverage.getId());
        data.put("goodsName", beverage.getGoodsName());
        data.put("quantity", quantity);
        data.put("price", beverage.getPrice());
        data.put("image", beverage.getImage());

        String confirmToken = AgentConfirmTokenUtil.generateToken();

        // 保存商品下单草稿到 Redis，按 userId + sessionId 隔离。
        draftStore.save(
                userId,
                context.getSessionId(),
                new PendingDraft(
                        PendingDraftType.SHOPPING,
                        data,
                        LocalDateTime.now(),
                        confirmToken
                )
        );

        BigDecimal total = beverage.getPrice().multiply(BigDecimal.valueOf(quantity));

        String message = "我已生成商品下单草稿：\n"
                + "商品：" + beverage.getGoodsName() + "\n"
                + "数量：" + quantity + "\n"
                + "单价：" + beverage.getPrice() + "\n"
                + "总价：" + total + "\n"
                + "确认码：" + confirmToken + "\n"
                + "如果确认，请回复：确认下单 " + confirmToken + "\n"
                + "如果放弃，请回复：取消\n"
                + "注意：该草稿将在15分钟后自动过期。";

        return AgentToolExecuteResult.needConfirm(
                message,
                data,
                "confirm_shopping"
        ).addExtra("confirmToken", confirmToken);
    }
}

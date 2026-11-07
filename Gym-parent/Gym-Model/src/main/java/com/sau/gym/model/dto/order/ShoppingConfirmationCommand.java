package com.sau.gym.model.dto.order;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 作者:hfj
 * 功能:商品确认办理命令
 * 说明:
 * 1. 参数全部来自服务端草稿快照与可信身份，不来自模型或前端自由输入。
 * 2. confirmationId 是业务意图标识；priceSnapshot 为用户确认时的价格快照，
 *    与当前价格不一致时返回 PRICE_CHANGED。
 */
@Data
public class ShoppingConfirmationCommand {

    /**
     * 可信用户ID。
     */
    private Long userId;

    /**
     * 会话ID。
     */
    private String sessionId;

    /**
     * 业务确认标识。
     */
    private String confirmationId;

    /**
     * 商品ID。
     */
    private Long goodsId;

    /**
     * 商品名称（冗余，用于失败文案）。
     */
    private String goodsName;

    /**
     * 数量。
     */
    private Integer quantity;

    /**
     * 用户确认时的单价快照。
     */
    private BigDecimal priceSnapshot;
}

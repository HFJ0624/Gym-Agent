package com.sau.gym.model.vo.order;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 作者:hfj
 * 功能:商品确认办理结构化结果
 * 说明:
 * 1. 客服确认、普通商城 Controller 与 Trace 共用同一份业务结果；
 *    金额与订单字段全部来源于实际业务结果。
 * 2. replay=true 表示本次请求命中了同一 confirmationId 的已提交回执，
 *    订单/支付信息来自数据库真实记录，不是重复执行。
 */
@Data
public class OrderConfirmationResult {

    /**
     * 办理状态（OrderConfirmationStatus.name()）。
     */
    private String status;

    /**
     * 稳定错误码（如 OUT_OF_STOCK、GOODS_UNAVAILABLE、BALANCE_CONFLICT）。
     */
    private String errorCode;

    /**
     * 业务确认标识。
     */
    private String confirmationId;

    /**
     * 订单ID。
     */
    private Long orderId;

    /**
     * 订单号。
     */
    private String orderNo;

    /**
     * 实际总金额。
     */
    private BigDecimal totalAmount;

    /**
     * 支付状态：1 已支付，0 待支付；以数据库订单为准。
     */
    private Integer payStatus;

    /**
     * 是否为已提交结果的重放。
     */
    private boolean replay;

    /**
     * 用户可读说明。
     */
    private String message;

    /**
     * PRICE_CHANGED 时的最新商品快照（goodsId/goodsName/quantity/price），用于生成新草稿。
     */
    private java.util.Map<String, Object> latestSnapshot;
}

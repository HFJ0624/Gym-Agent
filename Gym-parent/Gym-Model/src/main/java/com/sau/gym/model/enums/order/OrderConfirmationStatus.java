package com.sau.gym.model.enums.order;

/**
 * 作者:hfj
 * 功能:商品确认办理结果状态
 * 说明:
 * 状态只来自真实业务执行与数据库结果，不由模型推断：
 * - PAID 仅在事务已提交且支付状态明确时返回；
 * - PENDING_PAYMENT 保留项目原有“余额不足创建待支付订单”的语义；
 * - RESULT_UNKNOWN 表示提交结果待定，需通过只读查询核实。
 */
public enum OrderConfirmationStatus {

    /**
     * 已支付。
     */
    PAID,

    /**
     * 待支付（订单已创建，余额不足等）。
     */
    PENDING_PAYMENT,

    /**
     * 库存不足，本次办理未成功。
     */
    OUT_OF_STOCK,

    /**
     * 价格变化，已生成新价格草稿等待再次确认。
     */
    PRICE_CHANGED,

    /**
     * 草稿无效（不存在、过期、确认码错误等）。
     */
    INVALID_DRAFT,

    /**
     * 同一确认标识仍在执行中。
     */
    IN_PROGRESS,

    /**
     * 结果待定，需要只读查询核实。
     */
    RESULT_UNKNOWN,

    /**
     * 业务失败（商品下架、数量非法、余额冲突等）。
     */
    BUSINESS_FAILED
}

package com.sau.gym.admin.service.impl;

import com.sau.gym.admin.mapper.BeverageMapper;
import com.sau.gym.admin.mapper.OrderItemMapper;
import com.sau.gym.admin.mapper.OrderMapper;
import com.sau.gym.admin.mapper.ShoppingConfirmationMapper;
import com.sau.gym.admin.mapper.UserBalanceMapper;
import com.sau.gym.admin.mapper.BalanceRecordMapper;
import com.sau.gym.admin.mapper.PaymentRecordMapper;
import com.sau.gym.common.exception.SauException;
import com.sau.gym.model.dto.order.ShoppingConfirmationCommand;
import com.sau.gym.model.entity.event.NotificationEvent;
import com.sau.gym.model.entity.finance.BalanceRecord;
import com.sau.gym.model.entity.finance.PaymentRecord;
import com.sau.gym.model.entity.order.Order;
import com.sau.gym.model.entity.order.OrderItem;
import com.sau.gym.model.entity.order.ShoppingConfirmation;
import com.sau.gym.model.entity.shopping.Beverage;
import com.sau.gym.model.entity.user.UserBalance;
import com.sau.gym.admin.enums.NotificationBusinessTypeEnum;
import com.sau.gym.admin.enums.NotificationTypeEnum;
import com.sau.gym.model.enums.order.OrderConfirmationStatus;
import com.sau.gym.model.vo.order.OrderConfirmationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Date;

/**
 * 作者:hfj
 * 功能:商品确认交易核心（唯一真实事务实现）
 * 说明:
 * 1. 回执声明、订单、明细、库存、余额、支付/余额流水在同一个数据库事务中提交；
 *    事务回滚时全部一起回滚。
 * 2. 同一 confirmationId 的重复执行由回执主键唯一约束收敛：
 *    命中已提交回执时返回同一 orderId/orderNo 的真实支付状态（replay）。
 * 3. 库存与余额使用数据库条件更新（stock >= quantity / balance >= amount），
 *    影响行数为零表示本次扣减未成功，绝不读取旧值再覆盖。
 * 4. 结果状态只能由本类基于执行事实产出，模型不参与判断。
 * 5. 普通商城与客服确认复用本核心；调用方负责适配各自入口。
 */
@Service
public class ShoppingOrderTxService {

    private static final Logger log = LoggerFactory.getLogger(ShoppingOrderTxService.class);

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private BeverageMapper beverageMapper;

    @Autowired
    private UserBalanceMapper userBalanceMapper;

    @Autowired
    private BalanceRecordMapper balanceRecordMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private ShoppingConfirmationMapper confirmationMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /**
     * 商品确认事务核心。
     * 注意：本方法必须经 Spring 代理调用（从其他 Bean 调用）以保证事务生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderConfirmationResult confirm(ShoppingConfirmationCommand command) {
        Date now = new Date();

        //1. 回执查询：已提交且归属匹配的回执直接重放真实结果。
        ShoppingConfirmation existing = confirmationMapper.selectByConfirmationId(command.getConfirmationId());

        if (existing != null) {
            return replay(command, existing);
        }

        //2. 商品校验：存在、启用、数量合法、价格与确认快照一致。
        Beverage beverage = beverageMapper.selectById(command.getGoodsId());

        if (beverage == null || beverage.getStatus() == null || beverage.getStatus() != 1) {
            return fail(command, OrderConfirmationStatus.BUSINESS_FAILED, "GOODS_UNAVAILABLE",
                    "下单失败：商品不存在或已下架。本次未扣款、未扣库存。");
        }

        if (command.getQuantity() == null || command.getQuantity() <= 0) {
            return fail(command, OrderConfirmationStatus.BUSINESS_FAILED, "INVALID_QUANTITY",
                    "下单失败：商品数量不合法。");
        }

        if (beverage.getPrice().compareTo(command.getPriceSnapshot()) != 0) {
            OrderConfirmationResult result = fail(command, OrderConfirmationStatus.PRICE_CHANGED, "PRICE_CHANGED",
                    "商品价格已变化：确认时 " + command.getPriceSnapshot() + " 元/件，现价 "
                            + beverage.getPrice() + " 元/件。已为你生成新价格的待确认草稿，请确认后再下单。");
            result.setLatestSnapshot(buildLatestSnapshot(command, beverage));
            return result;
        }

        //3. 条件扣库存：零行表示库存不足，本次没有任何业务写入。
        if (beverageMapper.decreaseStock(command.getGoodsId(), command.getQuantity(), now) == 0) {
            return fail(command, OrderConfirmationStatus.OUT_OF_STOCK, "OUT_OF_STOCK",
                    "下单失败：库存不足（确认后库存发生变化）。本次未扣款。"
                            + "你可以稍后重试，或修改数量后重新下单。");
        }

        //4. 声明回执（IN_PROGRESS）：唯一键冲突说明另一事务已提交，转为结果重放。
        try {
            ShoppingConfirmation confirmation = new ShoppingConfirmation();
            confirmation.setConfirmationId(command.getConfirmationId());
            confirmation.setUserId(command.getUserId());
            confirmation.setSessionId(command.getSessionId());
            confirmation.setSnapshotHash(snapshotHash(command));
            confirmation.setStatus(OrderConfirmationStatus.IN_PROGRESS.name());
            confirmation.setCreateTime(now);

            confirmationMapper.insertOne(confirmation);
        } catch (DuplicateKeyException e) {
            //同一业务意图已被并发确认：读取已提交回执并重放。
            ShoppingConfirmation committed = confirmationMapper.selectByConfirmationId(command.getConfirmationId());

            if (committed != null) {
                return replay(command, committed);
            }

            throw new SauException(500, "确认标识冲突且回执暂时不可读，请稍后查询结果");
        }

        //5. 订单创建与支付处理（同一事务）。
        BigDecimal total = beverage.getPrice().multiply(BigDecimal.valueOf(command.getQuantity()));

        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setRemark("智能客服确认下单");
        order.setUserId(command.getUserId());
        order.setTotalPrice(total);
        order.setCreateTime(now);
        order.setUpdateTime(now);

        Integer payStatus;

        UserBalance userBalance = userBalanceMapper.GetBalanceById(command.getUserId());

        if (userBalance == null) {
            //余额记录缺失：抛出使事务回滚（库存/回执声明一起回滚），结果明确。
            throw new SauException(500, "余额记录异常，本次下单未成功");
        }

        if (userBalance.getBalance() != null
                && userBalance.getBalance().compareTo(total) >= 0) {
            //条件扣减余额：零行表示并发变化，整体回滚。
            if (userBalanceMapper.decreaseBalance(command.getUserId(), total, now) == 0) {
                throw new SauException(500, "余额并发变化，本次下单未成功，请重试");
            }

            BigDecimal afterBalance = userBalanceMapper.getBalanceValue(command.getUserId());

            BigDecimal beforeBalance = afterBalance == null
                    ? userBalance.getBalance()
                    : afterBalance.add(total);

            //余额流水（before/after 使用同事务权威数值）。
            BalanceRecord balanceRecord = new BalanceRecord();
            balanceRecord.setUserId(command.getUserId());
            balanceRecord.setOrderNo(order.getOrderNo());
            balanceRecord.setAmount(total);
            balanceRecord.setType(2);
            balanceRecord.setBeforeBalance(beforeBalance);
            balanceRecord.setAfterBalance(afterBalance == null
                    ? userBalance.getBalance().subtract(total) : afterBalance);
            balanceRecord.setCreateTime(now);
            balanceRecord.setRemark("智能客服下单,订单金额:" + total + "元");
            balanceRecordMapper.insertOne(balanceRecord);

            //支付流水。
            PaymentRecord paymentRecord = new PaymentRecord();
            paymentRecord.setPayNo(generatePayNo());
            paymentRecord.setPayChannel(1);
            paymentRecord.setCreateTime(now);
            paymentRecord.setAmount(total);
            paymentRecord.setUserId(command.getUserId());
            paymentRecord.setOrderNo(order.getOrderNo());
            paymentRecord.setStatus(1);
            paymentRecord.setOrderType(2);
            paymentRecord.setPayTime(now);
            paymentRecordMapper.insertOne(paymentRecord);

            order.setStatus(1);
            order.setPayTime(now);
            payStatus = 1;
        } else {
            //沿用项目原有语义：余额不足创建待支付订单。
            order.setStatus(0);
            payStatus = 0;
        }

        orderMapper.insertOrder(order);

        //6. 订单明细。
        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(order.getId());
        orderItem.setGoodsId(beverage.getId());
        orderItem.setGoodsName(beverage.getGoodsName());
        orderItem.setOrderNo(order.getOrderNo());
        orderItem.setQuantity(command.getQuantity());
        orderItem.setPrice(beverage.getPrice());
        orderItem.setImage(beverage.getImage());
        orderItemMapper.insertOrderItem(orderItem);

        //7. 同事务回填回执结果。
        String resultStatus = payStatus == 1
                ? OrderConfirmationStatus.PAID.name()
                : OrderConfirmationStatus.PENDING_PAYMENT.name();

        confirmationMapper.updateResult(
                command.getConfirmationId(), order.getId(), order.getOrderNo(), resultStatus);

        //8. 通知事件（AFTER_COMMIT 监听器发送，只随首次成功交易产生）。
        String notice = payStatus == 1
                ? "您的商城订单已支付成功。订单号：" + order.getOrderNo()
                : "您的商城订单已创建，当前待支付。订单号：" + order.getOrderNo();

        eventPublisher.publishEvent(new NotificationEvent(
                command.getUserId(),
                "商城订单确认结果",
                notice,
                NotificationTypeEnum.MALL.getCode(),
                order.getId(),
                order.getOrderNo(),
                NotificationBusinessTypeEnum.MALL_ORDER.getCode()
        ));

        OrderConfirmationResult result = new OrderConfirmationResult();
        result.setStatus(resultStatus);
        result.setConfirmationId(command.getConfirmationId());
        result.setOrderId(order.getId());
        result.setOrderNo(order.getOrderNo());
        result.setTotalAmount(total);
        result.setPayStatus(payStatus);
        result.setReplay(false);
        result.setMessage(payStatus == 1
                ? "下单成功：\n商品：" + beverage.getGoodsName() + "\n数量：" + command.getQuantity()
                + "\n实付金额：" + total + " 元\n订单号：" + order.getOrderNo()
                : "订单已创建，当前待支付：\n商品：" + beverage.getGoodsName() + "\n数量：" + command.getQuantity()
                + "\n应付金额：" + total + " 元\n订单号：" + order.getOrderNo()
                + "\n（余额不足，未完成支付；库存已按本单预留。）");

        return result;
    }

    /**
     * 回执重放：返回同一已提交订单的真实状态。
     */
    private OrderConfirmationResult replay(ShoppingConfirmationCommand command,
                                           ShoppingConfirmation existing) {
        return buildReplayResult(command.getUserId(), existing);
    }

    /**
     * 由已提交回执构造结果（归属校验；供确认与只读查询复用）。
     */
    public OrderConfirmationResult buildReplayResult(Long userId, ShoppingConfirmation existing) {
        //归属校验：他人 confirmationId 不泄露任何订单信息。
        if (!userId.equals(existing.getUserId())) {
            OrderConfirmationResult denied = new OrderConfirmationResult();
            denied.setStatus(OrderConfirmationStatus.BUSINESS_FAILED.name());
            denied.setErrorCode("CONFIRMATION_OWNED_BY_OTHER");
            denied.setConfirmationId(existing.getConfirmationId());
            denied.setMessage("该确认标识不存在或无权查看。");
            return denied;
        }

        Order order = existing.getOrderId() == null
                ? null
                : orderMapper.selectByOrderId(existing.getOrderId());

        OrderConfirmationResult result = new OrderConfirmationResult();
        result.setConfirmationId(existing.getConfirmationId());
        result.setReplay(true);

        if (order == null) {
            //回执存在但订单为空：办理仍在进行或异常，按结果待定处理。
            result.setStatus(OrderConfirmationStatus.RESULT_UNKNOWN.name());
            result.setErrorCode("RECEIPT_WITHOUT_ORDER");
            result.setMessage("该确认正在处理中或结果待核实，请稍后重新查询。");
            return result;
        }

        boolean paid = order.getStatus() != null && order.getStatus() == 1;

        result.setStatus(paid
                ? OrderConfirmationStatus.PAID.name()
                : OrderConfirmationStatus.PENDING_PAYMENT.name());
        result.setOrderId(order.getId());
        result.setOrderNo(order.getOrderNo());
        result.setTotalAmount(order.getTotalPrice());
        result.setPayStatus(order.getStatus());
        result.setMessage(paid
                ? "该确认已完成（重复确认已自动定位原订单）：\n订单号：" + order.getOrderNo()
                + "\n实付金额：" + order.getTotalPrice() + " 元\n未重复扣款。"
                : "该确认已有待支付订单（重复确认已自动定位原订单）：\n订单号：" + order.getOrderNo()
                + "\n应付金额：" + order.getTotalPrice() + " 元\n未重复创建订单。");

        return result;
    }

    /**
     * 构造失败结果。
     */
    private OrderConfirmationResult fail(ShoppingConfirmationCommand command,
                                         OrderConfirmationStatus status,
                                         String errorCode,
                                         String message) {
        OrderConfirmationResult result = new OrderConfirmationResult();
        result.setStatus(status.name());
        result.setErrorCode(errorCode);
        result.setConfirmationId(command.getConfirmationId());
        result.setReplay(false);
        result.setMessage(message);
        return result;
    }

    /**
     * 构造最新商品快照（PRICE_CHANGED 时生成新草稿用）。
     */
    private java.util.Map<String, Object> buildLatestSnapshot(ShoppingConfirmationCommand command,
                                                              Beverage beverage) {
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("goodsId", beverage.getId());
        snapshot.put("goodsName", beverage.getGoodsName());
        snapshot.put("quantity", command.getQuantity());
        snapshot.put("price", beverage.getPrice());
        snapshot.put("image", beverage.getImage());
        return snapshot;
    }

    /**
     * 服务端快照摘要（商品/数量/价格的 SHA-256 指纹）。
     * 当前用途：随回执持久留存，用于事后诊断与人工比对确认时的商品/数量/价格；
     * 不做运行时校验分支（价格一致性由 PRICE_CHANGED 分支基于真实价格判断）。
     */
    private String snapshotHash(ShoppingConfirmationCommand command) {
        String raw = command.getGoodsId() + "|" + command.getQuantity() + "|"
                + command.getPriceSnapshot();

        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }

            return builder.toString().substring(0, 32);
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    /**
     * 32 位订单号。
     */
    private String generateOrderNo() {
        return getSecureRandomNumber(32);
    }

    /**
     * 20 位支付流水号。
     */
    private String generatePayNo() {
        return getSecureRandomNumber(20);
    }

    private String getSecureRandomNumber(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            builder.append(random.nextInt(10));
        }

        return builder.toString();
    }
}

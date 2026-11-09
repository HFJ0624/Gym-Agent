package com.sau.gym.admin.service.impl;

import com.sau.gym.admin.mapper.BalanceRecordMapper;
import com.sau.gym.admin.mapper.BeverageMapper;
import com.sau.gym.admin.mapper.OrderItemMapper;
import com.sau.gym.admin.mapper.OrderMapper;
import com.sau.gym.admin.mapper.PaymentRecordMapper;
import com.sau.gym.admin.mapper.ShoppingConfirmationMapper;
import com.sau.gym.admin.mapper.UserBalanceMapper;
import com.sau.gym.common.exception.SauException;
import com.sau.gym.model.dto.order.ShoppingConfirmationCommand;
import com.sau.gym.model.entity.order.Order;
import com.sau.gym.model.entity.order.ShoppingConfirmation;
import com.sau.gym.model.entity.shopping.Beverage;
import com.sau.gym.model.entity.user.UserBalance;
import com.sau.gym.model.enums.order.OrderConfirmationStatus;
import com.sau.gym.model.vo.order.OrderConfirmationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:商品确认交易核心单元测试（幂等占位顺序版）
 * 说明:
 * 1. 回执占位必须先于库存/余额/订单写入（inOrder 校验），
 *    重复请求在扣库存之前被唯一键拦截。
 * 2. 占位后的明确失败（库存不足/价格变化/商品下架）抛 BusinessSignal，
 *    由门面在事务回滚后映射回原有业务状态。
 * 3. 并发/回滚的真实 MySQL 集成验证见 ShoppingConfirmationMySqlIT。
 */
class ShoppingOrderTxServiceTest {

    private ShoppingOrderTxService txService;

    private BeverageMapper beverageMapper;

    private OrderMapper orderMapper;

    private ShoppingConfirmationMapper confirmationMapper;

    private UserBalanceMapper userBalanceMapper;

    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        txService = new ShoppingOrderTxService();

        beverageMapper = mock(BeverageMapper.class);
        orderMapper = mock(OrderMapper.class);
        confirmationMapper = mock(ShoppingConfirmationMapper.class);
        userBalanceMapper = mock(UserBalanceMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        ReflectionTestUtils.setField(txService, "beverageMapper", beverageMapper);
        ReflectionTestUtils.setField(txService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(txService, "orderItemMapper", mock(OrderItemMapper.class));
        ReflectionTestUtils.setField(txService, "userBalanceMapper", userBalanceMapper);
        ReflectionTestUtils.setField(txService, "balanceRecordMapper", mock(BalanceRecordMapper.class));
        ReflectionTestUtils.setField(txService, "paymentRecordMapper", mock(PaymentRecordMapper.class));
        ReflectionTestUtils.setField(txService, "confirmationMapper", confirmationMapper);
        ReflectionTestUtils.setField(txService, "eventPublisher", eventPublisher);
    }

    private ShoppingConfirmationCommand command(String confirmationId) {
        ShoppingConfirmationCommand command = new ShoppingConfirmationCommand();
        command.setUserId(1L);
        command.setSessionId("session-a");
        command.setConfirmationId(confirmationId);
        command.setGoodsId(100L);
        command.setGoodsName("矿泉水");
        command.setQuantity(2);
        command.setPriceSnapshot(new BigDecimal("2.00"));
        return command;
    }

    private Beverage beverage() {
        Beverage beverage = new Beverage();
        beverage.setId(100L);
        beverage.setGoodsName("矿泉水");
        beverage.setPrice(new BigDecimal("2.00"));
        beverage.setStock(10);
        beverage.setStatus(1);
        return beverage;
    }

    @Test
    void 占位必须先于库存扣减() {
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);
        when(confirmationMapper.insertOne(any(ShoppingConfirmation.class))).thenReturn(1);
        when(beverageMapper.selectById(100L)).thenReturn(beverage());
        when(beverageMapper.decreaseStock(eq(100L), eq(2), any())).thenReturn(1);

        UserBalance balance = new UserBalance();
        balance.setUserId(1L);
        balance.setBalance(new BigDecimal("100.00"));
        when(userBalanceMapper.GetBalanceById(1L)).thenReturn(balance);
        when(userBalanceMapper.decreaseBalance(eq(1L), any(), any())).thenReturn(1);
        when(userBalanceMapper.getBalanceValue(1L)).thenReturn(new BigDecimal("96.00"));

        txService.confirm(command("c1"));

        //回执占位先于条件扣库存：重复请求在扣库存前被唯一键拦截。
        InOrder inOrder = inOrder(confirmationMapper, beverageMapper);
        inOrder.verify(confirmationMapper).insertOne(any(ShoppingConfirmation.class));
        inOrder.verify(beverageMapper).decreaseStock(eq(100L), eq(2), any());
    }

    @Test
    void 确认时库存已为0应抛信号且不写订单() {
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);
        when(confirmationMapper.insertOne(any(ShoppingConfirmation.class))).thenReturn(1);
        when(beverageMapper.selectById(100L)).thenReturn(beverage());
        when(beverageMapper.decreaseStock(eq(100L), eq(2), any())).thenReturn(0);

        ShoppingOrderTxService.BusinessSignal signal = assertThrows(ShoppingOrderTxService.BusinessSignal.class,
                () -> txService.confirm(command("c1")));

        assertEquals(OrderConfirmationStatus.OUT_OF_STOCK.name(), signal.getResult().getStatus());
        assertTrue(signal.getResult().getMessage().contains("库存不足"));
        assertTrue(signal.getResult().getMessage().contains("未扣款"));

        //事务回滚语义：不写订单、不回填回执、不扣款。
        verify(orderMapper, never()).insertOrder(any());
        verify(confirmationMapper, never()).updateResult(anyString(), any(), anyString(), anyString());
        verify(userBalanceMapper, never()).decreaseBalance(any(), any(), any());
    }

    @Test
    void 商品下架应抛信号返回明确业务失败() {
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);
        when(confirmationMapper.insertOne(any(ShoppingConfirmation.class))).thenReturn(1);

        Beverage offShelf = beverage();
        offShelf.setStatus(0);
        when(beverageMapper.selectById(100L)).thenReturn(offShelf);

        ShoppingOrderTxService.BusinessSignal signal = assertThrows(ShoppingOrderTxService.BusinessSignal.class,
                () -> txService.confirm(command("c1")));

        assertEquals(OrderConfirmationStatus.BUSINESS_FAILED.name(), signal.getResult().getStatus());
        assertEquals("GOODS_UNAVAILABLE", signal.getResult().getErrorCode());
        verify(beverageMapper, never()).decreaseStock(any(), any(), any());
    }

    @Test
    void 价格变化应抛信号并携带最新快照() {
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);
        when(confirmationMapper.insertOne(any(ShoppingConfirmation.class))).thenReturn(1);

        Beverage changed = beverage();
        changed.setPrice(new BigDecimal("2.50"));
        when(beverageMapper.selectById(100L)).thenReturn(changed);

        ShoppingOrderTxService.BusinessSignal signal = assertThrows(ShoppingOrderTxService.BusinessSignal.class,
                () -> txService.confirm(command("c1")));

        assertEquals(OrderConfirmationStatus.PRICE_CHANGED.name(), signal.getResult().getStatus());
        assertEquals(new BigDecimal("2.50"), signal.getResult().getLatestSnapshot().get("price"));
        verify(beverageMapper, never()).decreaseStock(any(), any(), any());
        verify(orderMapper, never()).insertOrder(any());
    }

    @Test
    void 余额条件扣款零行应抛异常整体回滚且不写订单() {
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);
        when(confirmationMapper.insertOne(any(ShoppingConfirmation.class))).thenReturn(1);
        when(beverageMapper.selectById(100L)).thenReturn(beverage());
        when(beverageMapper.decreaseStock(eq(100L), eq(2), any())).thenReturn(1);

        UserBalance balance = new UserBalance();
        balance.setUserId(1L);
        balance.setBalance(new BigDecimal("100.00"));
        when(userBalanceMapper.GetBalanceById(1L)).thenReturn(balance);
        //并发变化：条件扣款零行。
        when(userBalanceMapper.decreaseBalance(eq(1L), any(), any())).thenReturn(0);

        //事务方法内抛出 SauException：调用方门面映射为明确业务失败，事务回滚。
        assertThrows(SauException.class, () -> txService.confirm(command("c1")));
        verify(orderMapper, never()).insertOrder(any());
        verify(confirmationMapper, never()).updateResult(anyString(), any(), anyString(), anyString());
    }

    @Test
    void 已支付主路径应写入订单与回执并发送通知() {
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);
        when(confirmationMapper.insertOne(any(ShoppingConfirmation.class))).thenReturn(1);
        when(beverageMapper.selectById(100L)).thenReturn(beverage());
        when(beverageMapper.decreaseStock(eq(100L), eq(2), any())).thenReturn(1);

        UserBalance balance = new UserBalance();
        balance.setUserId(1L);
        balance.setBalance(new BigDecimal("100.00"));
        when(userBalanceMapper.GetBalanceById(1L)).thenReturn(balance);
        when(userBalanceMapper.decreaseBalance(eq(1L), any(), any())).thenReturn(1);
        when(userBalanceMapper.getBalanceValue(1L)).thenReturn(new BigDecimal("96.00"));

        OrderConfirmationResult result = txService.confirm(command("c1"));

        assertEquals(OrderConfirmationStatus.PAID.name(), result.getStatus());
        verify(orderMapper).insertOrder(any(Order.class));
        verify(confirmationMapper).updateResult(eq("c1"), any(), anyString(),
                eq(OrderConfirmationStatus.PAID.name()));
        //publishEvent 有 Object/ApplicationEvent 两个重载，按实际调用重载核验。
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(Object.class));
    }

    @Test
    void 重复确认应重放已提交回执且不重复扣款() {
        ShoppingConfirmation committed = new ShoppingConfirmation();
        committed.setConfirmationId("c1");
        committed.setUserId(1L);
        committed.setOrderId(9L);
        committed.setOrderNo("NO123");
        committed.setStatus(OrderConfirmationStatus.PAID.name());

        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(committed);

        Order order = new Order();
        order.setId(9L);
        order.setOrderNo("NO123");
        order.setStatus(1);
        order.setTotalPrice(new BigDecimal("4.00"));
        when(orderMapper.selectByOrderId(9L)).thenReturn(order);

        OrderConfirmationResult result = txService.confirm(command("c1"));

        assertEquals(OrderConfirmationStatus.PAID.name(), result.getStatus());
        assertTrue(result.isReplay());
        assertEquals("NO123", result.getOrderNo());
        assertTrue(result.getMessage().contains("未重复扣款"));

        //重放不触发任何业务写入（含占位与扣库存）。
        verify(confirmationMapper, never()).insertOne(any());
        verify(beverageMapper, never()).decreaseStock(any(), any(), any());
        verify(orderMapper, never()).insertOrder(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 他人确认标识不得泄露订单信息() {
        ShoppingConfirmation others = new ShoppingConfirmation();
        others.setConfirmationId("c2");
        others.setUserId(2L);
        others.setOrderId(9L);
        others.setOrderNo("NO999");

        when(confirmationMapper.selectByConfirmationId("c2")).thenReturn(others);

        OrderConfirmationResult result = txService.confirm(command("c2"));

        assertEquals(OrderConfirmationStatus.BUSINESS_FAILED.name(), result.getStatus());
        assertTrue(!result.getMessage().contains("NO999"));
        verify(orderMapper, never()).selectByOrderId(any());
    }
}

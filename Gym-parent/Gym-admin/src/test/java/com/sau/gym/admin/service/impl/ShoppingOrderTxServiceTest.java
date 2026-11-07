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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:商品确认交易核心单元测试（第四轮 P0）
 * 说明:
 * 覆盖：库存竞争失败、回执重放、越权拒绝、价格变化、商品下架、
 * 余额条件扣款冲突与已支付主路径。并发/回滚的真实 MySQL 集成验证
 * 见 ShoppingConfirmationMySqlIT（环境受限时标注未执行）。
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
    void 确认时库存已为0应返回OUT_OF_STOCK且无任何业务写入() {
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);
        when(beverageMapper.selectById(100L)).thenReturn(beverage());
        when(beverageMapper.decreaseStock(eq(100L), eq(2), any())).thenReturn(0);

        OrderConfirmationResult result = txService.confirm(command("c1"));

        assertEquals(OrderConfirmationStatus.OUT_OF_STOCK.name(), result.getStatus());
        assertTrue(result.getMessage().contains("库存不足"));
        assertTrue(result.getMessage().contains("未扣款"));

        //库存不足：无订单、无回执声明、无扣款流水。
        verify(orderMapper, never()).insertOrder(any());
        verify(confirmationMapper, never()).insertOne(any());
        verify(userBalanceMapper, never()).decreaseBalance(any(), any(), any());
    }

    @Test
    void 商品下架应返回明确业务失败() {
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);

        Beverage offShelf = beverage();
        offShelf.setStatus(0);
        when(beverageMapper.selectById(100L)).thenReturn(offShelf);

        OrderConfirmationResult result = txService.confirm(command("c1"));

        assertEquals(OrderConfirmationStatus.BUSINESS_FAILED.name(), result.getStatus());
        assertEquals("GOODS_UNAVAILABLE", result.getErrorCode());
        verify(beverageMapper, never()).decreaseStock(any(), any(), any());
    }

    @Test
    void 价格变化应返回PRICE_CHANGED并携带最新快照() {
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);

        Beverage changed = beverage();
        changed.setPrice(new BigDecimal("2.50"));
        when(beverageMapper.selectById(100L)).thenReturn(changed);

        OrderConfirmationResult result = txService.confirm(command("c1"));

        assertEquals(OrderConfirmationStatus.PRICE_CHANGED.name(), result.getStatus());
        assertEquals(new BigDecimal("2.50"), result.getLatestSnapshot().get("price"));
        verify(beverageMapper, never()).decreaseStock(any(), any(), any());
        verify(orderMapper, never()).insertOrder(any());
    }

    @Test
    void 余额条件扣款零行应整体回滚且不插入订单() {
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);
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
        when(beverageMapper.selectById(100L)).thenReturn(beverage());
        when(beverageMapper.decreaseStock(eq(100L), eq(2), any())).thenReturn(1);

        UserBalance balance = new UserBalance();
        balance.setUserId(1L);
        balance.setBalance(new BigDecimal("100.00"));
        when(userBalanceMapper.GetBalanceById(1L)).thenReturn(balance);
        when(userBalanceMapper.decreaseBalance(eq(1L), any(), any())).thenReturn(1);
        when(userBalanceMapper.getBalanceValue(1L)).thenReturn(new BigDecimal("96.00"));

        txService.confirm(command("c1"));

        verify(orderMapper).insertOrder(any(Order.class));
        verify(confirmationMapper).insertOne(any(ShoppingConfirmation.class));
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

        //重放不触发任何业务写入。
        verify(beverageMapper, never()).decreaseStock(any(), any(), any());
        verify(orderMapper, never()).insertOrder(any());
        verify(eventPublisher, never()).publishEvent(any());
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
        assertFalse(result.getMessage().contains("NO999"));
        verify(orderMapper, never()).selectByOrderId(any());
    }
}

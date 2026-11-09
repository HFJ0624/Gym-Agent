package com.sau.gym.admin.service.impl;

import com.sau.gym.admin.mapper.ShoppingConfirmationMapper;
import com.sau.gym.common.exception.SauException;
import com.sau.gym.model.dto.order.ShoppingConfirmationCommand;
import com.sau.gym.model.entity.order.ShoppingConfirmation;
import com.sau.gym.model.enums.order.OrderConfirmationStatus;
import com.sau.gym.model.vo.order.OrderConfirmationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:商品确认门面映射测试（幂等冲突重放路径）
 * 说明:
 * 占位唯一键冲突时事务已回滚，门面重新读取已提交回执：
 * 归属匹配 → 重放原订单；回执缺失 → RESULT_UNKNOWN。
 */
class OrderConfirmationFacadeTest {

    private OrderServiceImpl facade;

    private ShoppingOrderTxService txService;

    private ShoppingConfirmationMapper confirmationMapper;

    @BeforeEach
    void setUp() {
        facade = new OrderServiceImpl();
        txService = mock(ShoppingOrderTxService.class);
        confirmationMapper = mock(ShoppingConfirmationMapper.class);

        ReflectionTestUtils.setField(facade, "shoppingOrderTxService", txService);
        ReflectionTestUtils.setField(facade, "shoppingConfirmationMapper", confirmationMapper);
    }

    private ShoppingConfirmationCommand command() {
        ShoppingConfirmationCommand command = new ShoppingConfirmationCommand();
        command.setUserId(1L);
        command.setSessionId("session-a");
        command.setConfirmationId("c1");
        command.setGoodsId(100L);
        command.setQuantity(1);
        command.setPriceSnapshot(new BigDecimal("2.00"));
        return command;
    }

    @Test
    void 占位冲突且回执已提交时应重放原订单() {
        when(txService.confirm(any())).thenThrow(new ShoppingOrderTxService.ConfirmationConflictSignal());

        ShoppingConfirmation committed = new ShoppingConfirmation();
        committed.setConfirmationId("c1");
        committed.setUserId(1L);
        committed.setOrderId(9L);
        committed.setOrderNo("NO123");
        committed.setStatus(OrderConfirmationStatus.PAID.name());
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(committed);

        when(txService.buildReplayResult(eq(1L), any())).thenAnswer(invocation -> {
            OrderConfirmationResult result = new OrderConfirmationResult();
            result.setStatus(OrderConfirmationStatus.PAID.name());
            result.setOrderNo("NO123");
            result.setReplay(true);
            return result;
        });

        OrderConfirmationResult result = facade.confirmShopping(command());

        assertEquals(OrderConfirmationStatus.PAID.name(), result.getStatus());
        assertEquals("NO123", result.getOrderNo());
        assertTrue(result.isReplay());
    }

    @Test
    void 占位冲突且回执缺失时应返回结果待核实() {
        when(txService.confirm(any())).thenThrow(new ShoppingOrderTxService.ConfirmationConflictSignal());
        when(confirmationMapper.selectByConfirmationId("c1")).thenReturn(null);

        OrderConfirmationResult result = facade.confirmShopping(command());

        assertEquals(OrderConfirmationStatus.RESULT_UNKNOWN.name(), result.getStatus());
        assertEquals("CONFIRMATION_CONFLICT", result.getErrorCode());
    }

    @Test
    void 占位后明确失败应映射回原有业务状态() {
        OrderConfirmationResult outOfStock = new OrderConfirmationResult();
        outOfStock.setStatus(OrderConfirmationStatus.OUT_OF_STOCK.name());
        outOfStock.setErrorCode("OUT_OF_STOCK");
        outOfStock.setMessage("下单失败：库存不足。本次未扣款。");

        when(txService.confirm(any())).thenThrow(new ShoppingOrderTxService.BusinessSignal(outOfStock));

        OrderConfirmationResult result = facade.confirmShopping(command());

        assertEquals(OrderConfirmationStatus.OUT_OF_STOCK.name(), result.getStatus());
        assertEquals("OUT_OF_STOCK", result.getErrorCode());
        assertTrue(result.getMessage().contains("未扣款"));
    }

    @Test
    void 事务明确回滚异常应映射为业务失败() {
        when(txService.confirm(any())).thenThrow(new SauException(500, "余额并发变化"));

        OrderConfirmationResult result = facade.confirmShopping(command());

        assertEquals(OrderConfirmationStatus.BUSINESS_FAILED.name(), result.getStatus());
        assertTrue(result.getMessage().contains("本次未扣款"));
    }

    @Test
    void 前置校验占位冲突读取失败不得编造成功() {
        when(txService.confirm(any())).thenThrow(new ShoppingOrderTxService.ConfirmationConflictSignal());
        //回执读取异常：不能编造成功结果。
        when(confirmationMapper.selectByConfirmationId("c1")).thenThrow(new RuntimeException("db down"));

        OrderConfirmationResult result = facade.confirmShopping(command());

        assertEquals(OrderConfirmationStatus.RESULT_UNKNOWN.name(), result.getStatus());
    }
}

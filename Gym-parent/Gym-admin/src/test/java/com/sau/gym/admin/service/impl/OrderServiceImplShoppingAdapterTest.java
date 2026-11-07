package com.sau.gym.admin.service.impl;

import com.sau.gym.common.exception.SauException;
import com.sau.gym.model.entity.shopping.Beverage;
import com.sau.gym.model.entity.shopping.Cart;
import com.sau.gym.model.entity.user.User;
import com.sau.gym.utils.AuthContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:普通商城下单适配回归（第四轮）
 * 说明:
 * 1. 库存不足必须抛出业务异常：HTTP 不再返回 SUCCESS 静默成功。
 * 2. 购物车越权必须拒绝。
 * 修复对应审查发现的“库存不足时仍可能回复下单成功”。
 */
class OrderServiceImplShoppingAdapterTest {

    private OrderServiceImpl orderService;

    private com.sau.gym.admin.mapper.CartMapper cartMapper;

    private com.sau.gym.admin.mapper.BeverageMapper beverageMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderService = new OrderServiceImpl();

        cartMapper = mock(com.sau.gym.admin.mapper.CartMapper.class);
        beverageMapper = mock(com.sau.gym.admin.mapper.BeverageMapper.class);

        ReflectionTestUtils.setField(orderService, "cartMapper", cartMapper);
        ReflectionTestUtils.setField(orderService, "beverageMapper", beverageMapper);

        User user = new User();
        user.setId(1L);
        AuthContextUtil.set(user);
    }

    @AfterEach
    void tearDown() {
        AuthContextUtil.remove();
    }

    @Test
    void 库存不足时应抛出业务异常而不是静默成功() {
        Cart cart = new Cart();
        cart.setId(5L);
        cart.setUserId(1L);
        cart.setGoodsId(100L);
        cart.setGoodsName("矿泉水");
        cart.setQuantity(2);

        when(cartMapper.getByCartId(5L)).thenReturn(cart);

        Beverage beverage = new Beverage();
        beverage.setId(100L);
        beverage.setGoodsName("矿泉水");
        beverage.setPrice(new BigDecimal("2.00"));
        beverage.setStock(1);
        beverage.setStatus(1);
        when(beverageMapper.selectById(100L)).thenReturn(beverage);

        com.sau.gym.model.dto.order.OrderDto orderDto = new com.sau.gym.model.dto.order.OrderDto();
        orderDto.setCartIds(java.util.List.of(5L));

        //修复前：静默 return → Controller 返回 SUCCESS 且草稿被清成“下单成功”。
        //修复后：显式业务异常。
        assertThrows(SauException.class, () -> orderService.CreateShoppingOrder(orderDto));
    }

    @Test
    void 越权使用他人购物车应被拒绝() {
        Cart others = new Cart();
        others.setId(6L);
        others.setUserId(2L);
        others.setGoodsId(100L);
        others.setQuantity(1);

        when(cartMapper.getByCartId(6L)).thenReturn(others);

        com.sau.gym.model.dto.order.OrderDto orderDto = new com.sau.gym.model.dto.order.OrderDto();
        orderDto.setCartIds(java.util.List.of(6L));

        assertThrows(SauException.class, () -> orderService.CreateShoppingOrder(orderDto));
        verify(beverageMapper, never()).decreaseStock(any(), eq(1), any());
    }
}

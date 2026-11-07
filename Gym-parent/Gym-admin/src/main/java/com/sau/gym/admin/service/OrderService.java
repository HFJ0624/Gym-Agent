package com.sau.gym.admin.service;

import com.github.pagehelper.PageInfo;
import com.sau.gym.model.dto.order.OrderDto;
import com.sau.gym.model.dto.order.OrdersDto;
import com.sau.gym.model.entity.order.Order;
import com.sau.gym.model.vo.order.OrderDetailVO;
import com.sau.gym.model.vo.order.OrderVO;

import java.util.Map;

public interface OrderService {

    //创建订单
    void CreateShoppingOrder(OrderDto orderDto);

    /**
     * 商品确认办理（客服链路，结构化结果）。
     * 事务核心在 ShoppingOrderTxService；本方法为非事务门面，负责异常到结果的映射。
     */
    com.sau.gym.model.vo.order.OrderConfirmationResult confirmShopping(
            com.sau.gym.model.dto.order.ShoppingConfirmationCommand command);

    /**
     * 按确认标识查询回执与订单真实状态（只读，归属校验）。
     */
    com.sau.gym.model.vo.order.OrderConfirmationResult queryConfirmation(
            Long userId, String confirmationId);

    //获取购物订单列表
    PageInfo<OrderDetailVO> getOrderList(Integer page, Integer limit, Integer status);

    //订单查询列表
    PageInfo<OrderVO> findByPage(Integer current, Integer limit, OrdersDto ordersDto);

    //统计七天的订单营业额数据
    Map<String, Object> getAllTurnover();
}

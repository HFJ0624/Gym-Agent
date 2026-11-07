package com.sau.gym.admin.mapper;

import com.sau.gym.model.dto.order.OrdersDto;
import com.sau.gym.model.entity.order.Order;
import com.sau.gym.model.vo.order.OrderVO;
import com.sau.gym.model.vo.system.TurnoverVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderMapper {

    //插入到数据库
    void insertOrder(Order order);

    //按订单ID查询订单（回执重放时读取实际支付状态）
    Order selectByOrderId(@Param("id") Long id);

    //获取购物订单列表
    List<Order> getOrderList(Long id,Integer status);

    //订单查询列表
    List<OrderVO> findByPage(OrdersDto ordersDto);

    //获取前一星期的营业额数据
    List<TurnoverVo> getOrderTurnover();
}

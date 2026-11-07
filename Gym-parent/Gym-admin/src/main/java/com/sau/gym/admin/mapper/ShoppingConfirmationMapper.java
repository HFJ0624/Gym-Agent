package com.sau.gym.admin.mapper;

import com.sau.gym.model.entity.order.ShoppingConfirmation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 作者:hfj
 * 功能:商品确认回执 Mapper
 * 说明:
 * confirmation_id 为主键：同一业务意图由数据库唯一约束收敛；
 * 回执与订单、库存、余额在同一事务中写入。
 */
@Mapper
public interface ShoppingConfirmationMapper {

    /**
     * 插入回执。confirmation_id 冲突时抛 DuplicateKeyException，由调用方转为结果重放。
     */
    int insertOne(ShoppingConfirmation confirmation);

    /**
     * 按确认标识查询回执。
     */
    ShoppingConfirmation selectByConfirmationId(@Param("confirmationId") String confirmationId);

    /**
     * 同事务内回填订单结果。
     */
    int updateResult(@Param("confirmationId") String confirmationId,
                     @Param("orderId") Long orderId,
                     @Param("orderNo") String orderNo,
                     @Param("status") String status);
}

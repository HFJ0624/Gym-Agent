package com.sau.gym.admin.mapper;

import com.sau.gym.model.dto.user.UserBalanceDto;
import com.sau.gym.model.entity.user.UserBalance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;

@Mapper
public interface UserBalanceMapper {

    //1.先从数据库查,如果数据库没有数据,插入一条余额为0的数据
    UserBalance GetBalanceById(Long id);

    //插入一条数据
    void insertOne(Long id);

    //前台用户充值余额
    void Recharge(@Param("userBalanceDto")UserBalanceDto userBalanceDto,@Param("date") Date date);

    //更新用户余额
    void updateBalance(Long userId, BigDecimal surplus,Date date);

    //条件扣减余额：balance >= amount 才扣减；返回影响行数，零行表示余额不足或并发变化
    int decreaseBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount, @Param("date") Date date);

    //条件增加余额（退款等），返回影响行数
    int increaseBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount, @Param("date") Date date);

    //查询用户余额数值（用于流水 before/after 的权威数值）
    BigDecimal getBalanceValue(@Param("userId") Long userId);
}

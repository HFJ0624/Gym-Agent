package com.sau.gym.admin.mapper;

import com.sau.gym.model.dto.shopping.BeverageDto;
import com.sau.gym.model.entity.shopping.Beverage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface BeverageMapper {

    //零食饮品查询方法
    List<Beverage> findByPage(BeverageDto beverageDto);

    //添加商品
    void saveBeverage(Beverage beverage);

    //修改商品
    void updateBeverage(Beverage beverage);

    //删除商品
    void deleteById(Long beverageId, Date date);

    //前台查询商品的详情信息
    Beverage selectById(Long id);

    //更新库存
    void updateStock(Long id,Integer stock,Date date);

    //条件扣减库存：stock >= quantity 才扣减，返回影响行数；零行表示本次扣减未成功
    int decreaseStock(@Param("id") Long id, @Param("quantity") Integer quantity, @Param("date") Date date);

    //根据商品名查数据库商品信息
    Beverage selectByName(String productName);

    //查询库存不足的商品
    List<Beverage> findStock();
}

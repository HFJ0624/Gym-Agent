package com.sau.gym.admin.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.sau.gym.admin.enums.NotificationBusinessTypeEnum;
import com.sau.gym.admin.enums.NotificationTypeEnum;
import com.sau.gym.admin.mapper.*;
import com.sau.gym.admin.service.OrderService;
import com.sau.gym.common.exception.SauException;
import com.sau.gym.model.dto.order.OrderDto;
import com.sau.gym.model.dto.order.OrdersDto;
import com.sau.gym.model.entity.event.NotificationEvent;
import com.sau.gym.model.entity.finance.BalanceRecord;
import com.sau.gym.model.entity.finance.PaymentRecord;
import com.sau.gym.model.entity.order.Order;
import com.sau.gym.model.entity.order.ShoppingConfirmation;
import com.sau.gym.model.entity.order.OrderItem;
import com.sau.gym.model.entity.shopping.Beverage;
import com.sau.gym.model.entity.shopping.Cart;
import com.sau.gym.model.entity.user.User;
import com.sau.gym.model.entity.user.UserBalance;
import com.sau.gym.model.entity.venue.CourtBooking;
import com.sau.gym.model.vo.order.OrderDetailVO;
import com.sau.gym.model.vo.order.OrderVO;
import com.sau.gym.model.vo.system.TurnoverVo;
import com.sau.gym.utils.AuthContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 作者:hfj
 * 功能:
 * 日期: 2026/4/12 14:46
 */
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private CartMapper cartMapper;

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
    private CourtBookingMapper courtBookingMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ShoppingOrderTxService shoppingOrderTxService;

    @Autowired
    private ShoppingConfirmationMapper shoppingConfirmationMapper;

    /**
     * 商品确认办理门面（客服链路）。
     * 异常映射：
     * - SauException：事务已明确回滚，结果为明确业务失败；
     * - 其他异常：提交结果无法确定，返回 RESULT_UNKNOWN 并提示只读查询核实。
     */
    @Override
    public com.sau.gym.model.vo.order.OrderConfirmationResult confirmShopping(
            com.sau.gym.model.dto.order.ShoppingConfirmationCommand command) {
        try {
            return shoppingOrderTxService.confirm(command);
        } catch (ShoppingOrderTxService.ConfirmationConflictSignal e) {
            //占位唯一键冲突：当前事务已回滚，重新读取已提交回执做幂等重放。
            //重读失败不得编造成功，统一按结果待核实处理。
            try {
                ShoppingConfirmation committed =
                        shoppingConfirmationMapper.selectByConfirmationId(command.getConfirmationId());

                if (committed != null && command.getUserId().equals(committed.getUserId())) {
                    return shoppingOrderTxService.buildReplayResult(command.getUserId(), committed);
                }
            } catch (Exception readError) {
                // fall through to RESULT_UNKNOWN
            }

            com.sau.gym.model.vo.order.OrderConfirmationResult unknown =
                    new com.sau.gym.model.vo.order.OrderConfirmationResult();
            unknown.setStatus(com.sau.gym.model.enums.order.OrderConfirmationStatus.RESULT_UNKNOWN.name());
            unknown.setErrorCode("CONFIRMATION_CONFLICT");
            unknown.setConfirmationId(command.getConfirmationId());
            unknown.setMessage("相同确认正在处理中，结果待核实。请稍后通过确认记录查询，不要盲目重复确认。");
            return unknown;
        } catch (ShoppingOrderTxService.BusinessSignal e) {
            //占位后的明确业务失败：事务已回滚（占位与库存/余额/订单写入一起回退），原样返回业务状态。
            return e.getResult();
        } catch (SauException e) {
            com.sau.gym.model.vo.order.OrderConfirmationResult result =
                    new com.sau.gym.model.vo.order.OrderConfirmationResult();
            result.setStatus(com.sau.gym.model.enums.order.OrderConfirmationStatus.BUSINESS_FAILED.name());
            result.setErrorCode("TX_BUSINESS_FAILED");
            result.setConfirmationId(command == null ? null : command.getConfirmationId());
            result.setMessage("下单未成功：" + e.getMessage() + "。本次未扣款，可重新发起确认。");
            return result;
        } catch (Exception e) {
            com.sau.gym.model.vo.order.OrderConfirmationResult result =
                    new com.sau.gym.model.vo.order.OrderConfirmationResult();
            result.setStatus(com.sau.gym.model.enums.order.OrderConfirmationStatus.RESULT_UNKNOWN.name());
            result.setErrorCode("TX_RESULT_UNKNOWN");
            result.setConfirmationId(command == null ? null : command.getConfirmationId());
            result.setMessage("本次下单结果待核实，请稍后通过确认记录查询，不要盲目重复确认。");
            return result;
        }
    }

    /**
     * 按确认标识查询回执与订单真实状态（只读，归属校验）。
     */
    @Override
    public com.sau.gym.model.vo.order.OrderConfirmationResult queryConfirmation(
            Long userId, String confirmationId) {
        ShoppingConfirmation confirmation = shoppingConfirmationMapper.selectByConfirmationId(confirmationId);

        if (confirmation == null) {
            return null;
        }

        if (!userId.equals(confirmation.getUserId())) {
            //他人确认标识不泄露任何订单信息。
            return null;
        }

        return shoppingOrderTxService.buildReplayResult(userId, confirmation);
    }

    //创建订单
    @Transactional
    @Override
    public void CreateShoppingOrder(OrderDto orderDto) {

        //获取当前的用户信息
        User user = AuthContextUtil.get();

        //1.获取选中的购物车id
        List<Long> cartIds = orderDto.getCartIds();
        Cart cart = null;
        Beverage beverage;

        //2.校验购物车归属与库存。
        //归属校验：防止越权使用他人购物车条目。
        //库存不足必须显式抛出业务异常：原实现静默 return 会被上层当成成功。
        for (Long cartId : cartIds){
            cart = cartMapper.getByCartId(cartId);

            if (cart == null || !user.getId().equals(cart.getUserId())) {
                throw new SauException(500, "购物车不存在或无权操作");
            }

            beverage = beverageMapper.selectById(cart.getGoodsId());

            if (beverage == null || beverage.getStatus() == null || beverage.getStatus() != 1) {
                throw new SauException(500, "商品不存在或已下架：" + cart.getGoodsName());
            }

            if (beverage.getStock() < cart.getQuantity()){
                throw new SauException(500, "库存不足：" + cart.getGoodsName()
                        + " 剩余 " + beverage.getStock() + " 件");
            }
        }

        BigDecimal totalPrice = BigDecimal.ZERO;
        //3.计算订单总金额
        for (Long cartId : cartIds) {
            //获取购物车商品信息
            cart = cartMapper.getByCartId(cartId);
            BigDecimal itemTotal = cart.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity()));

            //累加
            totalPrice = totalPrice.add(itemTotal);
        }

        String order_no = getSecure32RandomNumber();
        //4.创建订单
        Order order = new Order();
        Date date = new Date();
        order.setPayTime(date);
        order.setOrderNo(order_no);
        order.setRemark(orderDto.getRemark());
        order.setUserId(user.getId());
        order.setTotalPrice(totalPrice);
        UserBalance userBalance = userBalanceMapper.GetBalanceById(user.getId());
        if (userBalance.getBalance().compareTo(totalPrice) >= 0){
            //用余额支付并更新余额
            order.setStatus(1);

            //插入用户余额流水信息
            BalanceRecord balanceRecord = new BalanceRecord();
            balanceRecord.setUserId(user.getId());
            balanceRecord.setOrderNo(order_no);
            balanceRecord.setAmount(totalPrice);
            balanceRecord.setType(2);
            balanceRecord.setCreateTime(new Date());
            //条件扣减余额：零行表示并发变化，整体回滚（订单/流水一起回退）。
            //先扣款并确认成功，再从同一事务读取扣款后的权威余额，
            //填好变动前后余额后插入流水（两列为 NOT NULL，禁止先插后填）。
            int balanceRows = userBalanceMapper.decreaseBalance(user.getId(), totalPrice, date);
            if (balanceRows == 0) {
                throw new SauException(500, "余额并发变化，本次下单未成功，请重试");
            }

            BigDecimal afterBalance = userBalanceMapper.getBalanceValue(user.getId());
            if (afterBalance == null) {
                throw new SauException(500, "余额流水读取异常，本次下单未成功");
            }

            balanceRecord.setBeforeBalance(afterBalance.add(totalPrice));
            balanceRecord.setAfterBalance(afterBalance);
            balanceRecordMapper.insertOne(balanceRecord);

            //插入支付流水信息
            PaymentRecord paymentRecord = new PaymentRecord();
            paymentRecord.setPayNo(get20SerialNo());
            paymentRecord.setPayChannel(1); //余额支付
            paymentRecord.setCreateTime(date);
            paymentRecord.setAmount(totalPrice);
            paymentRecord.setUserId(user.getId());
            paymentRecord.setOrderNo(order_no);
            paymentRecord.setStatus(1);
            paymentRecord.setOrderType(2);
            paymentRecord.setPayTime(date);
            paymentRecordMapper.insertOne(paymentRecord);

            Date dateTime;
            //更新库存
            for (Long cartId : cartIds){
                cart = cartMapper.getByCartId(cartId);
                beverage = beverageMapper.selectById(cart.getGoodsId());
                //条件扣减库存：stock >= quantity 才扣；零行表示并发变化，整体回滚。
                dateTime = new Date();
                int rows = beverageMapper.decreaseStock(beverage.getId(), cart.getQuantity(), dateTime);
                if (rows == 0) {
                    throw new SauException(500, "库存并发变化，本次下单未成功，请重试");
                }
            }

        }else {
            //余额不够支付,显示为待支付
            order.setStatus(0);
        }
        order.setCreateTime(date);
        order.setUpdateTime(date);
        //插入到数据库
        orderMapper.insertOrder(order);

        //监听事件:获取通知消息-用户下单商城消息
        eventPublisher.publishEvent(new NotificationEvent(
                user.getId(),
                "商城订单已下单",
                "您的商城订单已创建，已完成支付。订单号：" + order_no,
                NotificationTypeEnum.MALL.getCode(),
                order.getId(),
                order_no,
                NotificationBusinessTypeEnum.MALL_ORDER.getCode()
        ));

        //5.插入订单明细
        OrderItem orderItem;
        for (Long cartId : cartIds){
            //获取购物车商品信息
            cart = cartMapper.getByCartId(cartId);
            orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setImage(cart.getImage());
            orderItem.setGoodsId(cart.getGoodsId());
            orderItem.setGoodsName(cart.getGoodsName());
            orderItem.setOrderNo(order.getOrderNo());
            orderItem.setQuantity(cart.getQuantity());
            orderItem.setPrice(cart.getPrice());

            //插入订单明细
            orderItemMapper.insertOrderItem(orderItem);
        }

        //6.删除购物车数据
        for (Long cartId : cartIds){
            cartMapper.DeleteCartItem(cartId);
        }

    }

    //获取购物订单列表
    @Override
    public PageInfo<OrderDetailVO> getOrderList(Integer page, Integer limit, Integer status) {
        PageHelper.startPage(page,limit);
        User user = AuthContextUtil.get();
        //获取购物订单列表
        List<Order> orderList = orderMapper.getOrderList(user.getId(),status);
        if (orderList == null){
            return null;
        }

        List<OrderDetailVO> orderDetailVOList = new ArrayList<>();
        for (Order order :orderList){
            int total = 0; //计算总数量
            OrderDetailVO orderDetailVO = new OrderDetailVO();
            orderDetailVO.setOrderNo(order.getOrderNo());
            orderDetailVO.setStatus(order.getStatus());
            orderDetailVO.setRemark(order.getRemark());
            orderDetailVO.setTotalAmount(order.getTotalPrice());
            orderDetailVO.setPayTime(order.getPayTime());
            List<OrderItem> orderItemList = orderItemMapper.getOrderById(order.getId());
            for (OrderItem orderItem : orderItemList) {
                total += orderItem.getQuantity();
            }
            orderDetailVO.setTotalQuantity(total);
            orderDetailVO.setItems(orderItemList);
            orderDetailVOList.add(orderDetailVO);
        }

        PageInfo<OrderDetailVO> pageInfo = new PageInfo<>(orderDetailVOList);
        return pageInfo;
    }

    //订单查询列表
    @Override
    public PageInfo<OrderVO> findByPage(Integer current, Integer limit, OrdersDto ordersDto) {
        PageHelper.startPage(current,limit);
        List<OrderVO> list = orderMapper.findByPage(ordersDto);
        PageInfo<OrderVO> pageInfo = new PageInfo<>(list);
        return pageInfo;
    }

    //统计七天的订单营业额数据
    @Override
    public Map<String, Object> getAllTurnover() {
        // 日期集合（格式：MM-dd）和金额集合（Double）
        List<String> dateList = new ArrayList<>();
        List<Double> money = new ArrayList<>();

        // 1. 获取订单和预约的7天营业额数据
        List<TurnoverVo> orderList = orderMapper.getOrderTurnover();
        List<TurnoverVo> courtBookingList = courtBookingMapper.getCourtBookTurnover();

        // 2. 把两个List转成Map<日期(yyyy-MM-dd), 金额(BigDecimal)>，方便按天相加
        Map<String, BigDecimal> orderMap = new HashMap<>();
        for (TurnoverVo vo : orderList) {
            orderMap.put(vo.getDay(), vo.getTurnover());
        }

        Map<String, BigDecimal> courtMap = new HashMap<>();
        for (TurnoverVo vo : courtBookingList) {
            courtMap.put(vo.getDay(), vo.getTurnover());
        }

        // 3. 生成最近7天的日期（今天 + 前6天），并计算每天的总营业额
        LocalDate today = LocalDate.now();
        // 日期格式化：和TurnoverVo里的day格式一致（yyyy-MM-dd）
        DateTimeFormatter fullFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        // 前端显示的格式（MM-dd）
        DateTimeFormatter shortFormatter = DateTimeFormatter.ofPattern("MM-dd");

        for (int i = 6; i >= 0; i--) {
            // 从今天往前推6天，循环7次（i=6到0，对应前6天到今天）
            LocalDate date = today.minusDays(i);
            String fullDateStr = date.format(fullFormatter);
            String shortDateStr = date.format(shortFormatter);

            // 加入日期集合
            dateList.add(shortDateStr);

            // 取出订单和预约的金额，没有的话用0
            BigDecimal orderAmount = orderMap.getOrDefault(fullDateStr, BigDecimal.ZERO);
            BigDecimal courtAmount = courtMap.getOrDefault(fullDateStr, BigDecimal.ZERO);

            // 金额相加，转成Double加入集合
            BigDecimal totalAmount = orderAmount.add(courtAmount);
            money.add(totalAmount.doubleValue());
        }

        // 4. 封装到Map返回
        Map<String, Object> map = new HashMap<>();
        map.put("dateList", dateList);
        map.put("money", money);
        return map;
    }

    /***
     *
     * @return 生成32位订单编码
     */
    public static String getSecure32RandomNumber() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);

        for (int i = 0; i < 32; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    /***
     *
     * @return 生成20位流水号（时间+随机数，纯数字）
     */
    public static String get20SerialNo() {
        long time = System.currentTimeMillis();
        String random = String.format("%07d", (int) (Math.random() * 10000000));
        return String.valueOf(time) + random;
    }
}

package com.sau.gym.admin.agent.tool;

import com.sau.gym.admin.agent.store.AgentDraftStore;
import com.sau.gym.admin.agent.store.PendingDraft;
import com.sau.gym.admin.agent.store.SessionWriteResult;
import com.sau.gym.admin.agent.store.SessionWriteTicket;
import com.sau.gym.admin.enums.PendingDraftType;
import com.sau.gym.admin.service.OrderService;
import com.sau.gym.model.dto.order.ShoppingConfirmationCommand;
import com.sau.gym.model.enums.order.OrderConfirmationStatus;
import com.sau.gym.model.vo.order.OrderConfirmationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 功能:商品确认结果隔离测试（收尾清理回归）
 * 说明:
 * 确认改为方法直接返回 OrderConfirmationResult 后：
 * 1. 两个不同用户并发确认各自获得自己的回执，无跨请求泄漏；
 * 2. 无草稿请求返回 INVALID_DRAFT，不带出其他请求的结果；
 * 3. 确认码错误同样返回本请求自己的结果。
 */
class ShoppingConfirmIsolationTest {

    private OrderService orderService;

    private AgentDraftStore draftStore;

    private GymShoppingTools tools;

    private final SessionWriteTicket ticket = SessionWriteTicket.of("lock", "token");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderService = mock(OrderService.class);
        draftStore = mock(AgentDraftStore.class);
        tools = new GymShoppingTools(orderService, draftStore);

        when(draftStore.saveIfHolder(any(), anyString(), any(), any())).thenReturn(SessionWriteResult.SAVED);
        when(draftStore.clearIfHolder(any(), anyString(), any())).thenReturn(SessionWriteResult.SAVED);
    }

    private PendingDraft shoppingDraft(Long userId, String confirmationId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goodsId", 100L);
        data.put("goodsName", "矿泉水-用户" + userId);
        data.put("quantity", 1);
        data.put("price", "2.00");
        data.put("confirmationId", confirmationId);

        return new PendingDraft(PendingDraftType.SHOPPING, data, LocalDateTime.now(), "123456");
    }

    private OrderConfirmationResult paidResult(Long userId, String confirmationId) {
        OrderConfirmationResult result = new OrderConfirmationResult();
        result.setStatus(OrderConfirmationStatus.PAID.name());
        result.setConfirmationId(confirmationId);
        result.setOrderId(userId * 10);
        result.setOrderNo("NO-USER-" + userId);
        result.setPayStatus(1);
        result.setMessage("下单成功：订单号 NO-USER-" + userId);
        return result;
    }

    @Test
    void 两个用户并发确认各自获得自己的回执() throws Exception {
        when(draftStore.get(eq(1L), anyString())).thenReturn(shoppingDraft(1L, "c-user-1"));
        when(draftStore.get(eq(2L), anyString())).thenReturn(shoppingDraft(2L, "c-user-2"));

        when(orderService.confirmShopping(any())).thenAnswer(invocation -> {
            ShoppingConfirmationCommand command = invocation.getArgument(0);
            return paidResult(command.getUserId(), command.getConfirmationId());
        });

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<OrderConfirmationResult>[] futures = new Future[2];

        for (long userId : new long[]{1L, 2L}) {
            final long uid = userId;
            futures[(int) (uid - 1)] = pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                return tools.confirmPendingShopping(uid, "session-a", "123456", ticket);
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        OrderConfirmationResult result1 = futures[0].get(10, TimeUnit.SECONDS);
        OrderConfirmationResult result2 = futures[1].get(10, TimeUnit.SECONDS);

        //各自拿到自己的回执：订单号、confirmationId 一一对应。
        assertEquals("NO-USER-1", result1.getOrderNo());
        assertEquals("c-user-1", result1.getConfirmationId());
        assertEquals("NO-USER-2", result2.getOrderNo());
        assertEquals("c-user-2", result2.getConfirmationId());

        assertFalse(result1.getMessage().contains("NO-USER-2"), "用户1的结果不得包含用户2的订单号");
        assertFalse(result2.getMessage().contains("NO-USER-1"), "用户2的结果不得包含用户1的订单号");
    }

    @Test
    void 无草稿请求返回INVALID_DRAFT且不带出其他请求的结果() {
        //用户2没有草稿；用户1存在草稿且曾成功过。
        when(draftStore.get(eq(2L), anyString())).thenReturn(null);

        OrderConfirmationResult result = tools.confirmPendingShopping(2L, "session-a", "123456", ticket);

        assertEquals(OrderConfirmationStatus.INVALID_DRAFT.name(), result.getStatus());
        assertFalse(result.getMessage().contains("NO-USER"), "无草稿请求不得带出任何其他请求的订单信息");
    }

    @Test
    void 确认码错误返回本请求自己的结果() {
        when(draftStore.get(eq(1L), anyString())).thenReturn(shoppingDraft(1L, "c-user-1"));

        OrderConfirmationResult result = tools.confirmPendingShopping(1L, "session-a", "wrong-token", ticket);

        assertEquals(OrderConfirmationStatus.INVALID_DRAFT.name(), result.getStatus());
        assertTrue(result.getMessage().contains("确认码错误"));
        verify(orderService, never()).confirmShopping(any());
    }

}

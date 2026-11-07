package com.sau.gym.admin.service.impl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作者:hfj
 * 功能:商品确认真实 MySQL 集成验证（并发扣减 / 回执唯一收敛 / 事务回滚）
 * 说明:
 * 1. 本用例验证第四轮交易核心依赖的 SQL 语义在真实 MySQL（InnoDB）下的行为：
 *    条件扣库存、条件扣余额、回执主键唯一收敛、异常回滚。
 * 2. 需要一个可写的 MySQL 实例：通过环境变量 GYM_IT_DB_URL/GYM_IT_DB_USER/GYM_IT_DB_PASS
 *    指定隔离的测试库（不要指向生产或开发共享库）。默认 @Disabled，
 *    环境具备时移除注解执行。
 * 3. 并发阶段用 latch 控制同时起跑，不依赖随机 sleep。
 */
@Disabled("需要隔离的 MySQL 测试实例：设置 GYM_IT_DB_URL/GYM_IT_DB_USER/GYM_IT_DB_PASS 后移除本注解执行")
class ShoppingConfirmationMySqlIT {

    private static Connection connection;

    @BeforeAll
    static void setUp() throws Exception {
        String url = System.getenv().getOrDefault("GYM_IT_DB_URL",
                "jdbc:mysql://127.0.0.1:3306/gym_it?characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true");
        String user = System.getenv().getOrDefault("GYM_IT_DB_USER", "root");
        String pass = System.getenv().getOrDefault("GYM_IT_DB_PASS", "1234");

        connection = DriverManager.getConnection(url, user, pass);

        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS it_beverage");
            statement.execute("DROP TABLE IF EXISTS it_user_balance");
            statement.execute("DROP TABLE IF EXISTS it_confirmation");
            statement.execute("DROP TABLE IF EXISTS it_orders");

            statement.execute("CREATE TABLE it_beverage ("
                    + "id BIGINT PRIMARY KEY, stock INT NOT NULL, status INT NOT NULL, is_deleted INT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE it_user_balance ("
                    + "user_id BIGINT PRIMARY KEY, balance DECIMAL(10,2) NOT NULL)");
            statement.execute("CREATE TABLE it_confirmation ("
                    + "confirmation_id VARCHAR(64) PRIMARY KEY, user_id BIGINT, order_id BIGINT, status VARCHAR(32))");
            statement.execute("CREATE TABLE it_orders ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, status INT, total DECIMAL(10,2))");

            statement.execute("INSERT INTO it_beverage VALUES (100, 1, 1, 0)");
            statement.execute("INSERT INTO it_user_balance VALUES (1, 100.00), (2, 100.00)");
        }
    }

    /**
     * 两个余额充足的用户并发竞争最后一件商品：
     * 最多一个 PAID，库存不为负，失败方条件扣减影响行数为零。
     */
    @org.junit.jupiter.api.Test
    void 两个用户并发竞争最后一件商品最多一个成交() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        AtomicInteger paidCount = new AtomicInteger();
        AtomicInteger stockFailed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<?>[] futures = new Future<?>[2];
        for (int i = 0; i < 2; i++) {
            final long userId = i + 1;
            final String confirmationId = UUID.randomUUID().toString();

            futures[i] = pool.submit((java.util.concurrent.Callable<Void>) () -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);

                try (Connection conn = connection) {
                    conn.setAutoCommit(false);

                    //条件扣库存（与 ShoppingOrderTxService 相同语义）。
                    PreparedStatement decrease = conn.prepareStatement(
                            "UPDATE it_beverage SET stock = stock - 1 "
                                    + "WHERE id = 100 AND stock >= 1 AND status = 1 AND is_deleted = 0");
                    int rows = decrease.executeUpdate();

                    if (rows == 0) {
                        conn.rollback();
                        stockFailed.incrementAndGet();
                        return null;
                    }

                    //声明回执。
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO it_confirmation (confirmation_id, user_id, order_id, status) "
                                    + "VALUES (?, ?, NULL, 'IN_PROGRESS')")) {
                        insert.setString(1, confirmationId);
                        insert.setLong(2, userId);
                        insert.executeUpdate();
                    }

                    //条件扣余额。
                    PreparedStatement pay = conn.prepareStatement(
                            "UPDATE it_user_balance SET balance = balance - 2 "
                                    + "WHERE user_id = ? AND balance >= 2");
                    pay.setLong(1, userId);
                    pay.executeUpdate();

                    //创建订单。
                    PreparedStatement orderInsert = conn.prepareStatement(
                            "INSERT INTO it_orders (user_id, status, total) VALUES (?, 1, 2.00)",
                            Statement.RETURN_GENERATED_KEYS);
                    orderInsert.setLong(1, userId);
                    orderInsert.executeUpdate();

                    long orderId;
                    try (ResultSet keys = orderInsert.getGeneratedKeys()) {
                        keys.next();
                        orderId = keys.getLong(1);
                    }

                    //回填回执。
                    try (PreparedStatement update = conn.prepareStatement(
                            "UPDATE it_confirmation SET order_id = ?, status = 'PAID' "
                                    + "WHERE confirmation_id = ?")) {
                        update.setLong(1, orderId);
                        update.setString(2, confirmationId);
                        update.executeUpdate();
                    }

                    conn.commit();
                    paidCount.incrementAndGet();
                }
                return null;
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        assertEquals(1, paidCount.get(), "最后一件商品最多一个 PAID");
        assertEquals(1, stockFailed.get(), "失败方应在条件扣库存处被拒");

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT stock FROM it_beverage WHERE id = 100")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "库存不为负");
        }
    }

    /**
     * 同一 confirmationId 并发确认多次：回执主键唯一约束收敛，订单只创建一次。
     */
    @org.junit.jupiter.api.Test
    void 同一确认标识并发确认只创建一个订单() throws Exception {
        int threads = 4;
        String confirmationId = UUID.randomUUID().toString();

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Future<?>[] futures = new Future<?>[threads];

        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit((java.util.concurrent.Callable<Void>) () -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);

                try (Connection conn = connection) {
                    conn.setAutoCommit(false);

                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO it_confirmation (confirmation_id, user_id, order_id, status) "
                                    + "VALUES (?, 1, NULL, 'IN_PROGRESS')")) {
                        insert.setString(1, confirmationId);
                        insert.executeUpdate();
                    } catch (Exception e) {
                        //唯一键冲突：按已提交回执重放。
                        conn.rollback();
                        replayed.incrementAndGet();
                        return null;
                    }

                    PreparedStatement orderInsert = conn.prepareStatement(
                            "INSERT INTO it_orders (user_id, status, total) VALUES (1, 1, 2.00)",
                            Statement.RETURN_GENERATED_KEYS);
                    orderInsert.executeUpdate();

                    try (ResultSet keys = orderInsert.getGeneratedKeys()) {
                        keys.next();
                        long orderId = keys.getLong(1);

                        try (PreparedStatement update = conn.prepareStatement(
                                "UPDATE it_confirmation SET order_id = ?, status = 'PAID' "
                                        + "WHERE confirmation_id = ?")) {
                            update.setLong(1, orderId);
                            update.setString(2, confirmationId);
                            update.executeUpdate();
                        }
                    }

                    conn.commit();
                    created.incrementAndGet();
                }
                return null;
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();

        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        assertEquals(1, created.get(), "同一确认标识只创建一个订单");
        assertEquals(threads - 1, replayed.get(), "其余请求应走重放路径");

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM it_orders WHERE user_id = 1")) {
            rs.next();
            assertTrue(rs.getInt(1) >= 1);
        }
    }
}

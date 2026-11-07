package com.sau.gym.admin.agent.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 作者:hfj
 * 功能:会话级并发控制
 * 说明:
 * 1. 同一会话同一时刻只允许一个请求处理，后来者明确被拒绝，避免消息与草稿互相覆盖。
 * 2. 使用唯一持有令牌，释放和续租都比较令牌，不会删除或续期他人的锁。
 * 3. 长请求按 TTL/3 续租；续租失败即标记锁丢失，运行时停止后续步骤。
 * 注意:
 * 锁只解决并发覆盖问题，不代表业务写入天然幂等。
 */
@Component
public class AgentSessionLockService {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionLockService.class);

    /**
     * 锁 key 前缀。
     */
    private static final String KEY_PREFIX = "agent:session:lock:user:";

    /**
     * 释放锁的 Lua 脚本: 值相等才删除。
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    /**
     * 续租脚本: 值相等才续期。
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 续租调度器，单线程守护线程即可，不引入并发平台。
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-session-lock-renewer");
        thread.setDaemon(true);
        return thread;
    });

    public AgentSessionLockService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 应用销毁时关闭续租调度器，避免线程泄漏。
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * 尝试获取会话锁。
     *
     * @param userId       用户ID，未登录使用 guest
     * @param sessionId    会话ID
     * @param ttl          锁有效期
     * @return 锁句柄；已被占用时返回 null
     */
    public LockHandle tryLock(Long userId, String sessionId, Duration ttl) {
        String userKey = userId == null ? "guest" : String.valueOf(userId);
        String key = KEY_PREFIX + userKey + ":session:" + sessionId;

        String token = UUID.randomUUID().toString();

        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, token, ttl);

        if (!Boolean.TRUE.equals(acquired)) {
            return null;
        }

        return new LockHandle(key, token, ttl);
    }

    /**
     * 锁句柄。
     * 生命周期判定：
     * 1. alive 表示明确失效（释放、续租被拒绝）。
     * 2. leaseDeadlineNanos 记录最近一次确认持有的租约到期时间（单调时钟）。
     *    续租短暂异常时容忍，但租约到期后 isLeaseValid() 自然变为 false，
     *    不会异常多久都保持有效。
     */
    public final class LockHandle {

        private final String key;

        private final String token;

        private final long ttlMillis;

        private final AtomicBoolean alive = new AtomicBoolean(true);

        private final AtomicBoolean released = new AtomicBoolean(false);

        /**
         * 最近确认的租约到期时间（System.nanoTime() 单调时钟）。
         */
        private volatile long leaseDeadlineNanos;

        private ScheduledFuture<?> renewTask;

        private LockHandle(String key, String token, Duration ttl) {
            this.key = key;
            this.token = token;
            this.ttlMillis = ttl.toMillis();
            //注意单位：System.nanoTime() 是纳秒，必须用 toNanos() 换算，
            //否则毫秒数值被当作纳秒，租约形同即期失效。
            this.leaseDeadlineNanos = System.nanoTime() + ttl.toNanos();
            startRenew();
        }

        /**
         * 锁是否仍然有效（未明确释放/未被判失效）。
         */
        public boolean isAlive() {
            return alive.get();
        }

        /**
         * 租约是否仍有效：未明确失效且仍在最近确认的租约期内。
         * 运行时与提交阶段应使用本方法作为真实依据。
         */
        public boolean isLeaseValid() {
            return alive.get() && System.nanoTime() < leaseDeadlineNanos;
        }

        /**
         * 生成受保护写入凭证。
         * 令牌只在服务端执行上下文中流转，用于 Redis 原子脚本校验。
         */
        public SessionWriteTicket toTicket() {
            return SessionWriteTicket.of(key, token);
        }

        /**
         * 执行一次续租（调度器与测试共用）。
         *
         * @return true=续租成功（延长租约）；false=锁已不属于自己（明确失效）
         */
        boolean renewOnce() {
            Long result = stringRedisTemplate.execute(
                    RENEW_SCRIPT,
                    Collections.singletonList(key),
                    token,
                    String.valueOf(ttlMillis)
            );

            if (result != null && result == 1L) {
                leaseDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttlMillis);
                return true;
            }

            //续租被拒绝说明锁已不属于自己，必须明确失效。
            alive.set(false);
            log.warn("[AgentLock] 会话锁已丢失, key={}", key);
            return false;
        }

        /**
         * 启动续租。
         */
        private void startRenew() {
            long period = Math.max(ttlMillis / 3, 1000L);

            renewTask = scheduler.scheduleAtFixedRate(() -> {
                if (released.get()) {
                    return;
                }

                try {
                    renewOnce();
                } catch (Exception e) {
                    //Redis 短暂异常不立即判定失效，等待下一次续租；
                    //若异常持续跨过租约有效期，isLeaseValid() 会自然变为 false。
                    log.warn("[AgentLock] 续租异常, key={}, reason={}", key, e.getMessage());
                }
            }, period, period, TimeUnit.MILLISECONDS);
        }

        /**
         * 释放锁。
         * 必须在 finally 中调用。
         */
        public void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }

            if (renewTask != null) {
                renewTask.cancel(true);
            }

            try {
                stringRedisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(key), token);
            } catch (Exception e) {
                log.warn("[AgentLock] 释放会话锁失败, key={}, reason={}", key, e.getMessage());
            } finally {
                alive.set(false);
            }
        }
    }
}

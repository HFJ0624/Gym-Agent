package com.sau.gym.admin.agent.run;

import com.sau.gym.admin.agent.runtime.AgentRunStatus;
import com.sau.gym.admin.agent.store.SessionWriteResult;
import com.sau.gym.admin.agent.store.SessionWriteTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 作者:hfj
 * 功能:Agent 运行记录存储
 * 说明:
 * 1. 运行记录与 latestRun 指针的写入全部走持有者保护脚本：
 *    Redis 侧原子校验锁令牌，旧请求/晚到请求不能覆盖新运行。
 * 2. 记录用 Redis Hash（字段级更新便于条件写），指针为 String。
 * 3. 中断识别：仅当记录仍为 RUNNING 且会话锁 key 已不存在（持有者已释放/过期）
 *    时条件写为 INTERRUPTED；锁仍存在说明运行可能仍活跃，不接管。
 * 4. 当前部署为单实例/哨兵 Redis；脚本多 key 要求同槽位，
 *    所有 key 共享前缀，集群部署前需确认 hash-slot 策略。
 */
@Component
public class AgentRunStore {

    private static final Logger log = LoggerFactory.getLogger(AgentRunStore.class);

    /**
     * 运行记录 key 前缀。
     */
    public static final String RUN_KEY_PREFIX = "agent:run:user:";

    /**
     * latestRun 指针 key 前缀。
     */
    public static final String POINTER_KEY_PREFIX = "agent:run:latest:user:";

    /**
     * 运行有效期与会话 6 小时保持一致；不承诺永久持久化。
     */
    private static final Duration TTL = Duration.ofHours(6);

    /**
     * 建立运行记录并指向 latestRun。
     * KEYS[1]=锁, KEYS[2]=runHash, KEYS[3]=pointer；
     * ARGV[1]=令牌, ARGV[2]=runId, ARGV[3]=ttlMs, ARGV[4]=now, 其余为字段对。
     */
    private static final DefaultRedisScript<Long> START_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end "
                    + "redis.call('del', KEYS[2]) "
                    + "redis.call('hset', KEYS[2], 'runId', ARGV[2], 'status', 'RUNNING') "
                    + "for i = 5, #ARGV, 2 do "
                    + "redis.call('hset', KEYS[2], ARGV[i], ARGV[i + 1]) "
                    + "end "
                    + "redis.call('pexpire', KEYS[2], ARGV[3]) "
                    + "redis.call('set', KEYS[3], ARGV[2], 'PX', ARGV[3]) "
                    + "return 1",
            Long.class
    );

    /**
     * 持有者保护更新运行字段。
     * KEYS[1]=锁, KEYS[2]=runHash；ARGV[1]=令牌, ARGV[2]=ttlMs, 其余为字段对。
     */
    private static final DefaultRedisScript<Long> UPDATE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end "
                    + "for i = 3, #ARGV, 2 do "
                    + "redis.call('hset', KEYS[2], ARGV[i], ARGV[i + 1]) "
                    + "end "
                    + "redis.call('pexpire', KEYS[2], ARGV[2]) "
                    + "return 1",
            Long.class
    );

    /**
     * 中断识别（条件写）：
     * 仅当 latestRun 仍为 RUNNING 且会话锁已不存在时改为 INTERRUPTED。
     * KEYS[1]=锁, KEYS[2]=pointer；ARGV[1]=runKey前缀, ARGV[2]=now。
     */
    private static final DefaultRedisScript<Long> INTERRUPT_SCRIPT = new DefaultRedisScript<>(
            "local runId = redis.call('get', KEYS[2]) "
                    + "if not runId then return 0 end "
                    + "local runKey = ARGV[1] .. runId "
                    + "local status = redis.call('hget', runKey, 'status') "
                    + "if not status or status ~= 'RUNNING' then return 0 end "
                    + "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
                    + "redis.call('hset', runKey, 'status', 'INTERRUPTED', 'updateTime', ARGV[2]) "
                    + "return 1",
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    public AgentRunStore(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 建立 RUNNING 运行记录。
     * 写入失败只记录日志并返回 false，不阻断本轮对话；恢复查询会如实显示无运行信息。
     */
    public boolean startRunning(Long userId, String sessionId, String runId,
                                String stage, SessionWriteTicket ticket) {
        if (userId == null || ticket == null) {
            return false;
        }

        try {
            Long result = stringRedisTemplate.execute(
                    START_SCRIPT,
                    List.of(ticket.lockKey(), runKey(userId, sessionId, runId), pointerKey(userId, sessionId)),
                    ticket.token(),
                    runId,
                    String.valueOf(TTL.toMillis()),
                    String.valueOf(System.currentTimeMillis()),
                    "userId", String.valueOf(userId),
                    "sessionId", sessionId,
                    "stage", stage == null ? AgentRunStage.CONSULTING.name() : stage
            );

            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("[AgentRun] 建立运行记录失败, runId={}, reason={}", runId, e.getMessage());
            return false;
        }
    }

    /**
     * 持有者保护更新阶段。
     */
    public boolean updateStage(Long userId, String sessionId, String runId,
                               String stage, SessionWriteTicket ticket) {
        return updateFields(userId, sessionId, runId, ticket,
                "stage", stage == null ? "" : stage,
                "updateTime", String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 持有者保护写入终态。
     */
    public boolean finish(Long userId, String sessionId, String runId,
                          AgentRunStatus status, String stage, String errorCode,
                          boolean sessionCommitted, long revision, SessionWriteTicket ticket) {
        return updateFields(userId, sessionId, runId, ticket,
                "status", status == null ? "" : status.name(),
                "stage", stage == null ? "" : stage,
                "errorCode", errorCode == null ? "" : errorCode,
                "sessionCommitted", String.valueOf(sessionCommitted),
                "revision", String.valueOf(revision),
                "updateTime", String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 读取当前会话最新运行。
     * Redis 异常返回 unavailable，与会话无运行区分。
     */
    public AgentRunLookup getLatest(Long userId, String sessionId) {
        if (userId == null) {
            return AgentRunLookup.of(null);
        }

        try {
            String runId = stringRedisTemplate.opsForValue().get(pointerKey(userId, sessionId));

            if (runId == null) {
                return AgentRunLookup.of(null);
            }

            var entries = stringRedisTemplate.opsForHash().entries(runKey(userId, sessionId, runId));

            if (entries == null || entries.isEmpty()) {
                return AgentRunLookup.of(null);
            }

            AgentRunRecord record = new AgentRunRecord();
            record.setRunId(asString(entries.get("runId"), runId));
            record.setUserId(userId);
            record.setSessionId(sessionId);
            record.setStatus(asString(entries.get("status"), null));
            record.setStage(asString(entries.get("stage"), null));
            record.setErrorCode(asString(entries.get("errorCode"), null));
            record.setSessionCommitted(asBoolean(entries.get("sessionCommitted")));
            record.setRevision(asLong(entries.get("revision")));
            record.setCreateTime(asLong(entries.get("createTime")));
            record.setUpdateTime(asLong(entries.get("updateTime")));

            return AgentRunLookup.of(record);
        } catch (Exception e) {
            log.warn("[AgentRun] 读取运行记录失败, sessionId={}, reason={}", sessionId, e.getMessage());
            return AgentRunLookup.unavailable();
        }
    }

    /**
     * 受控识别中断：条件写，已完成结果不会被反向改成 INTERRUPTED。
     *
     * @return true 表示本次确实把 RUNNING 改成了 INTERRUPTED
     */
    public boolean markInterruptedIfRunning(Long userId, String sessionId) {
        if (userId == null) {
            return false;
        }

        try {
            Long result = stringRedisTemplate.execute(
                    INTERRUPT_SCRIPT,
                    List.of(lockKey(userId, sessionId), pointerKey(userId, sessionId)),
                    RUN_KEY_PREFIX + userId + ":session:" + sessionId + ":",
                    String.valueOf(System.currentTimeMillis())
            );

            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("[AgentRun] 中断识别失败, sessionId={}, reason={}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 持有者保护更新字段。
     */
    private boolean updateFields(Long userId, String sessionId, String runId,
                                 SessionWriteTicket ticket, String... fields) {
        if (userId == null || ticket == null) {
            return false;
        }

        try {
            String[] args = new String[fields.length + 2];
            args[0] = ticket.token();
            args[1] = String.valueOf(TTL.toMillis());
            System.arraycopy(fields, 0, args, 2, fields.length);

            Long result = stringRedisTemplate.execute(
                    UPDATE_SCRIPT,
                    List.of(ticket.lockKey(), runKey(userId, sessionId, runId)),
                    (Object[]) args
            );

            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("[AgentRun] 更新运行记录失败, runId={}, reason={}", runId, e.getMessage());
            return false;
        }
    }

    /**
     * 锁 key 构造，与 AgentSessionLockService 保持一致。
     */
    private String lockKey(Long userId, String sessionId) {
        String userKey = userId == null ? "guest" : String.valueOf(userId);
        return "agent:session:lock:user:" + userKey + ":session:" + sessionId;
    }

    private String runKey(Long userId, String sessionId, String runId) {
        return RUN_KEY_PREFIX + userId + ":session:" + sessionId + ":" + runId;
    }

    private String pointerKey(Long userId, String sessionId) {
        return POINTER_KEY_PREFIX + userId + ":session:" + sessionId;
    }

    private String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }

        return Boolean.parseBoolean(String.valueOf(value));
    }
}

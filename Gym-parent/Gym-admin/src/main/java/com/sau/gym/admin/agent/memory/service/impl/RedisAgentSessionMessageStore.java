package com.sau.gym.admin.agent.memory.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.memory.model.SessionHistory;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.memory.service.AgentSessionMessageStore;
import com.sau.gym.admin.agent.store.SessionProtectedRedis;
import com.sau.gym.admin.agent.store.SessionWriteResult;
import com.sau.gym.admin.agent.store.SessionWriteTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 作者:hfj
 * 功能:基于 Redis 的会话协议消息存储
 * 说明:
 * 1. key 由 userId + sessionId 共同构成，保证同一用户不同会话隔离。
 * 2. 未登录用户使用 guest 占位，不会与登录用户串数据。
 * 3. 存储格式为 SessionHistory 快照（摘要 + 消息），兼容第一轮纯数组格式。
 * 4. 只保留最近若干条消息，避免无限增长。
 */
@Service
public class RedisAgentSessionMessageStore implements AgentSessionMessageStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAgentSessionMessageStore.class);

    /**
     * Redis key 前缀。
     */
    private static final String KEY_PREFIX = "agent:session:messages:";

    /**
     * 会话历史有效期，沿用原记忆服务的 6 小时。
     */
    private static final Duration TTL = Duration.ofHours(6);

    /**
     * 最多保留的消息条数。
     * 一轮带工具调用的对话可能产生 4~6 条消息，40 条约等于 7~8 轮。
     */
    private static final int MAX_MESSAGE_COUNT = 40;

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    /**
     * 持有者保护写入（Redis 原子校验锁令牌）。
     */
    private final SessionProtectedRedis protectedRedis;

    public RedisAgentSessionMessageStore(StringRedisTemplate stringRedisTemplate,
                                         ObjectMapper objectMapper,
                                         SessionProtectedRedis protectedRedis) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.protectedRedis = protectedRedis;
    }

    @Override
    public SessionHistory loadHistory(Long userId, String sessionId) {
        SessionHistory history = new SessionHistory();
        history.setMessages(new ArrayList<>());

        try {
            String json = stringRedisTemplate.opsForValue().get(buildKey(userId, sessionId));

            if (!StringUtils.hasText(json)) {
                return history;
            }

            String trimmed = json.trim();

            //兼容第一轮的纯消息数组格式。
            if (trimmed.startsWith("[")) {
                List<SessionMessage> messages = objectMapper.readValue(
                        trimmed,
                        new TypeReference<List<SessionMessage>>() {
                        }
                );
                history.setMessages(messages == null ? new ArrayList<>() : messages);
                return history;
            }

            SessionHistory stored = objectMapper.readValue(trimmed, SessionHistory.class);

            if (stored == null) {
                return history;
            }

            history.setSummary(stored.getSummary());
            history.setSummaryVersion(stored.getSummaryVersion());
            history.setRevision(stored.getRevision());
            history.setMessages(stored.getMessages() == null ? new ArrayList<>() : stored.getMessages());
            history.setDisplayTurns(stored.getDisplayTurns() == null
                    ? new ArrayList<>()
                    : stored.getDisplayTurns());

            return history;

        } catch (Exception e) {
            //读取失败必须与“正常没有历史”区分：失败时历史不可信，
            //调用方不得用空历史覆盖原记录。
            log.warn("[AgentSession] 读取会话历史失败, sessionId={}, reason={}", sessionId, e.getMessage());
            history.setLoadFailed(true);
            history.setLoadError(e.getMessage());
            return history;
        }
    }

    @Override
    public SessionWriteResult saveHistoryIfHolder(Long userId, String sessionId, SessionHistory history,
                                                  SessionWriteTicket ticket) {
        if (history == null) {
            return SessionWriteResult.SAVED;
        }

        if (ticket == null) {
            //Agent 主链不允许无令牌的受保护写入。
            return SessionWriteResult.LOCK_LOST;
        }

        try {
            String key = buildKey(userId, sessionId);

            List<SessionMessage> messages = history.getMessages() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(history.getMessages());

            while (messages.size() > MAX_MESSAGE_COUNT) {
                messages.remove(0);
            }

            //裁剪后仍需保证 tool 消息能找到对应的 assistant tool_calls。
            messages = dropOrphanToolMessages(messages);

            SessionHistory snapshot = new SessionHistory();
            snapshot.setSummary(history.getSummary());
            snapshot.setSummaryVersion(history.getSummaryVersion());
            //状态提交版本：在旧快照版本上递增，仅表示提交次数，不复用 summaryVersion。
            snapshot.setRevision(history.getRevision() + 1);
            snapshot.setMessages(messages);
            snapshot.setDisplayTurns(history.getDisplayTurns() == null
                    ? new ArrayList<>()
                    : history.getDisplayTurns());

            String json = objectMapper.writeValueAsString(snapshot);

            return protectedRedis.writeIfHolder(
                    ticket.lockKey(),
                    ticket.token(),
                    key,
                    json,
                    TTL
            );
        } catch (Exception e) {
            log.warn("[AgentSession] 受保护写入会话历史失败, sessionId={}, reason={}",
                    sessionId, e.getMessage());
            return SessionWriteResult.STORE_UNAVAILABLE;
        }
    }

    @Override
    public void clear(Long userId, String sessionId) {
        try {
            stringRedisTemplate.delete(buildKey(userId, sessionId));
        } catch (Exception e) {
            log.warn("[AgentSession] 清空会话历史失败, sessionId={}, reason={}", sessionId, e.getMessage());
        }
    }

    /**
     * 去掉开头孤立的 tool 消息。
     * 裁剪时如果正好把 assistant tool_calls 消息删掉，tool 消息就会变成非法历史。
     */
    private List<SessionMessage> dropOrphanToolMessages(List<SessionMessage> messages) {
        List<SessionMessage> result = new ArrayList<>(messages.size());

        Set<String> knownCallIds = new HashSet<>();

        for (SessionMessage message : messages) {
            if (message == null || !StringUtils.hasText(message.getRole())) {
                continue;
            }

            if ("assistant".equals(message.getRole())
                    && message.getToolCalls() != null
                    && !message.getToolCalls().isEmpty()) {
                message.getToolCalls().forEach(call -> {
                    if (call != null && StringUtils.hasText(call.getId())) {
                        knownCallIds.add(call.getId());
                    }
                });
                result.add(message);
                continue;
            }

            if ("tool".equals(message.getRole())) {
                if (StringUtils.hasText(message.getToolCallId())
                        && knownCallIds.contains(message.getToolCallId())) {
                    result.add(message);
                }
                continue;
            }

            result.add(message);
        }

        return result;
    }

    /**
     * 构造 Redis key。
     */
    private String buildKey(Long userId, String sessionId) {
        String userKey = userId == null ? "guest" : String.valueOf(userId);
        return KEY_PREFIX + userKey + ":" + sessionId;
    }
}

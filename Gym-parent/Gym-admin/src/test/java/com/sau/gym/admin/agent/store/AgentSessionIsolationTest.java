package com.sau.gym.admin.agent.store;

import com.sau.gym.admin.agent.memory.model.SessionHistory;
import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.admin.agent.memory.service.impl.RedisAgentSessionMessageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.enums.PendingDraftType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:会话隔离与旧 key 兼容测试
 * 说明:
 * 用内存 Map 模拟 Redis，验证 key 维度、会话隔离和 default 会话迁移逻辑。
 */
class AgentSessionIsolationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private Map<String, String> stringStore;

    private RedisAgentSessionMessageStore messageStore;

    private Map<String, PendingDraft> draftMap;

    private AgentDraftStore draftStore;

    /**
     * 构造测试用会话快照。
     */
    private SessionHistory historyOf(SessionMessage... messages) {
        SessionHistory history = new SessionHistory();
        history.setMessages(new ArrayList<>(List.of(messages)));
        return history;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stringStore = new HashMap<>();

        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> stringOps = mock(ValueOperations.class);

        when(stringRedisTemplate.opsForValue()).thenReturn(stringOps);
        doAnswer(invocation -> {
            stringStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(stringOps).set(anyString(), anyString(), any(Duration.class));
        when(stringOps.get(anyString())).thenAnswer(invocation -> stringStore.get(invocation.getArgument(0)));
        when(stringRedisTemplate.delete(anyString())).thenAnswer(invocation -> {
            stringStore.remove(invocation.getArgument(0));
            return true;
        });

        messageStore = new RedisAgentSessionMessageStore(stringRedisTemplate, objectMapper);

        draftMap = new HashMap<>();

        RedisTemplate<String, PendingDraft> draftRedisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, PendingDraft> draftOps = mock(ValueOperations.class);

        when(draftRedisTemplate.opsForValue()).thenReturn(draftOps);
        doAnswer(invocation -> {
            draftMap.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(draftOps).set(anyString(), any(PendingDraft.class), any(Duration.class));
        when(draftOps.get(anyString())).thenAnswer(invocation -> draftMap.get(invocation.getArgument(0)));
        when(draftRedisTemplate.delete(anyString())).thenAnswer(invocation -> {
            draftMap.remove(invocation.getArgument(0));
            return true;
        });

        draftStore = new AgentDraftStore(draftRedisTemplate);
    }

    @Test
    void 同一用户不同会话的消息历史互不影响() {
        messageStore.saveHistory(1L, "session-a", historyOf(
                SessionMessage.user("我是A会话"),
                SessionMessage.assistant("A会话回复")
        ));

        messageStore.saveHistory(1L, "session-b", historyOf(
                SessionMessage.user("我是B会话"),
                SessionMessage.assistant("B会话回复")
        ));

        List<SessionMessage> sessionA = messageStore.loadHistory(1L, "session-a").getMessages();
        List<SessionMessage> sessionB = messageStore.loadHistory(1L, "session-b").getMessages();

        assertEquals(2, sessionA.size());
        assertEquals("我是A会话", sessionA.get(0).getContent());

        assertEquals(2, sessionB.size());
        assertEquals("我是B会话", sessionB.get(0).getContent());
    }

    @Test
    void 不同用户相同sessionId也不串数据() {
        messageStore.saveHistory(1L, "shared", historyOf(SessionMessage.user("用户1")));
        messageStore.saveHistory(2L, "shared", historyOf(SessionMessage.user("用户2")));

        assertEquals("用户1", messageStore.loadHistory(1L, "shared").getMessages().get(0).getContent());
        assertEquals("用户2", messageStore.loadHistory(2L, "shared").getMessages().get(0).getContent());
    }

    @Test
    void 没有配对工具调用的历史应被丢弃() {
        SessionMessage orphanTool = SessionMessage.tool("call_missing", "query", "{}");

        messageStore.saveHistory(1L, "session-a", historyOf(
                SessionMessage.user("你好"),
                orphanTool,
                SessionMessage.assistant("你好")
        ));

        List<SessionMessage> messages = messageStore.loadHistory(1L, "session-a").getMessages();

        assertEquals(2, messages.size());
        assertTrue(messages.stream().noneMatch(message -> "tool".equals(message.getRole())));
    }

    @Test
    void 摘要与消息在同一次快照中保存() {
        SessionHistory history = new SessionHistory();
        history.setSummary("用户想预约A场馆羽毛球场，尚未确定时间。");
        history.setSummaryVersion(2);
        history.setMessages(List.of(
                SessionMessage.user("那明天晚上呢"),
                SessionMessage.assistant("已生成预约草稿")
        ));

        assertTrue(messageStore.saveHistory(1L, "session-s", history));

        SessionHistory loaded = messageStore.loadHistory(1L, "session-s");

        assertEquals("用户想预约A场馆羽毛球场，尚未确定时间。", loaded.getSummary());
        assertEquals(2, loaded.getSummaryVersion());
        assertEquals(2, loaded.getMessages().size());
    }

    @Test
    void 旧版纯数组历史可以兼容读取() {
        //模拟第一轮写入的纯消息数组。
        String legacyJson = "[{\"role\":\"user\",\"content\":\"旧消息\"}]";
        stringStore.put("agent:session:messages:1:session-legacy", legacyJson);

        SessionHistory loaded = messageStore.loadHistory(1L, "session-legacy");

        assertNull(loaded.getSummary());
        assertEquals(1, loaded.getMessages().size());
        assertEquals("旧消息", loaded.getMessages().get(0).getContent());
    }

    @Test
    void 草稿按会话隔离() {
        PendingDraft draft = new PendingDraft(
                PendingDraftType.BOOKING,
                Map.of("venueId", 1),
                LocalDateTime.now(),
                "111111"
        );

        draftStore.save(1L, "session-a", draft);

        assertNotNull(draftStore.get(1L, "session-a"));
        assertNull(draftStore.get(1L, "session-b"));
        assertNull(draftStore.get(2L, "session-a"));
    }

    @Test
    void default会话可以读取并迁移旧草稿() {
        PendingDraft legacy = new PendingDraft(
                PendingDraftType.BOOKING,
                Map.of("venueId", 9),
                LocalDateTime.now(),
                "222222"
        );

        //模拟历史版本写入的 userId 级 key。
        draftMap.put("agent:draft:user:1", legacy);

        PendingDraft loaded = draftStore.get(1L, "default");

        assertNotNull(loaded);
        assertEquals("222222", loaded.confirmToken());

        //迁移后旧 key 必须被删除，避免取消后旧草稿重新出现。
        assertFalse(draftMap.containsKey("agent:draft:user:1"));
        assertTrue(draftMap.containsKey("agent:draft:user:1:session:default"));
    }

    @Test
    void 新会话不读取旧userId级草稿() {
        draftMap.put("agent:draft:user:1", new PendingDraft(
                PendingDraftType.SHOPPING,
                Map.of("goodsId", 1),
                LocalDateTime.now(),
                "333333"
        ));

        assertNull(draftStore.get(1L, "session-new"));
    }
}

package com.sau.gym.admin.rag.retrieval;

import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.admin.mapper.CourtMapper;
import com.sau.gym.admin.mapper.VenueMapper;
import com.sau.gym.model.entity.venue.Court;
import com.sau.gym.model.entity.venue.Venue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 作者:hfj
 * 功能:知识检索范围解析器测试（R1 修复）
 * 说明:
 * 覆盖：上下文补齐、权威归属校验、仅 courtId 推导场馆、显式切换对象、
 * 校验服务不可用与比较参数解析。
 */
class KnowledgeScopeResolverTest {

    private CourtMapper courtMapper;

    private VenueMapper venueMapper;

    private KnowledgeScopeResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        courtMapper = mock(CourtMapper.class);
        venueMapper = mock(VenueMapper.class);
        resolver = new KnowledgeScopeResolver(courtMapper, venueMapper);

        //默认：场地 10 属于场馆 1，场馆 1/2 存在。
        when(courtMapper.selectOne(10L)).thenReturn(court(10L, 1L));
        when(courtMapper.selectOne(11L)).thenReturn(court(11L, 2L));
        when(venueMapper.selectVenueBatchByIds(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> venue(id)).toList();
        });
    }

    @Test
    void 模型未传范围时应从可信业务上下文补齐() {
        AgentBusinessContext context = new AgentBusinessContext();
        context.setLastVenueId(1L);
        context.setLastCourtId(10L);

        KnowledgeScopeResolver.ScopeResolution resolution = resolver.resolve(null, null, context);

        assertTrue(resolution.scoped);
        assertEquals(1L, resolution.venueId);
        assertEquals(10L, resolution.courtId);
    }

    @Test
    void 完全没有范围时进入无范围模式() {
        KnowledgeScopeResolver.ScopeResolution resolution = resolver.resolve(null, null, null);

        assertFalse(resolution.scoped);
        assertFalse(resolution.invalid);
        assertNull(resolution.venueId);
    }

    @Test
    void 仅给场地时应通过权威数据推导所属场馆() {
        KnowledgeScopeResolver.ScopeResolution resolution = resolver.resolve(null, 10L, null);

        assertTrue(resolution.scoped);
        assertEquals(1L, resolution.venueId, "场馆应从权威场地数据推导");
        assertEquals(10L, resolution.courtId);
    }

    @Test
    void 场地与场馆不一致时应判定无效() {
        //场地 10 属于场馆 1，请求给的却是场馆 2。
        KnowledgeScopeResolver.ScopeResolution resolution = resolver.resolve(2L, 10L, null);

        assertTrue(resolution.invalid);
        assertEquals("COURT_VENUE_MISMATCH", resolution.reason);
    }

    @Test
    void 场地不存在时应判定无效() {
        KnowledgeScopeResolver.ScopeResolution resolution = resolver.resolve(1L, 999L, null);

        assertTrue(resolution.invalid);
        assertEquals("COURT_NOT_FOUND", resolution.reason);
    }

    @Test
    void 场馆不存在时应判定无效() {
        when(venueMapper.selectVenueBatchByIds(anyList())).thenReturn(List.of());

        KnowledgeScopeResolver.ScopeResolution resolution = resolver.resolve(777L, null, null);

        assertTrue(resolution.invalid);
        assertEquals("VENUE_NOT_FOUND", resolution.reason);
    }

    @Test
    void 归属校验服务不可用时不得静默放行() {
        when(courtMapper.selectOne(10L)).thenThrow(new RuntimeException("db down"));

        KnowledgeScopeResolver.ScopeResolution resolution = resolver.resolve(1L, 10L, null);

        assertTrue(resolution.invalid);
        assertEquals("SCOPE_CHECK_UNAVAILABLE", resolution.reason);
    }

    @Test
    void 用户明确切换场馆时应以新对象为准() {
        AgentBusinessContext context = new AgentBusinessContext();
        context.setLastVenueId(1L);

        KnowledgeScopeResolver.ScopeResolution resolution = resolver.resolve(2L, null, context);

        assertTrue(resolution.scoped);
        assertEquals(2L, resolution.venueId, "显式给出场馆时不继承旧槽位");
        assertNull(resolution.courtId);
    }

    @Test
    void 显式给出的合法组合应通过归属校验() {
        KnowledgeScopeResolver.ScopeResolution resolution = resolver.resolve(1L, 10L, null);

        assertTrue(resolution.scoped);
        assertEquals(1L, resolution.venueId);
        assertEquals(10L, resolution.courtId);
    }

    @Test
    void 比较参数解析应支持合法列表并限制数量() {
        List<Long> ids = resolver.parseCompareVenueIds("1, 2，3");

        assertEquals(List.of(1L, 2L, 3L), ids);

        //超过 5 个截断到 5 个。
        List<Long> capped = resolver.parseCompareVenueIds("1,2,3,4,5,6,7");
        assertEquals(5, capped.size());
    }

    @Test
    void 非法比较参数应返回Null由调用方报参数错误() {
        assertNull(resolver.parseCompareVenueIds(null));
        assertNull(resolver.parseCompareVenueIds(""));
        assertNull(resolver.parseCompareVenueIds("1,abc"));
        assertNull(resolver.parseCompareVenueIds("  "));
    }

    // ------------------------------------------------------------------
    // 构造辅助
    // ------------------------------------------------------------------

    private Court court(Long id, Long venueId) {
        Court court = new Court();
        court.setId(id);
        court.setVenueId(venueId);
        court.setName("场地" + id);
        return court;
    }

    private Venue venue(Long id) {
        Venue venue = new Venue();
        venue.setId(id);
        venue.setVenueName("场馆" + id);
        return venue;
    }
}

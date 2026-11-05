package com.sau.gym.admin.agent.sop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作者:hfj
 * 功能:客服 SOP 目录测试
 * 说明:
 * 验证目录加载、按意图选择与正文加载；
 * SOP 内容来自服务端资源，不会加载用户资料。
 */
class AgentSopCatalogTest {

    private AgentSopCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new AgentSopCatalog(new ObjectMapper());
        catalog.init();
    }

    @Test
    void 目录应加载全部场景() {
        assertEquals(4, catalog.size());
        assertEquals("1.0.0", catalog.getCatalogVersion());
    }

    @Test
    void 按意图选择对应SOP() {
        SopDefinition cancel = catalog.select("BOOKING_CANCEL", false);
        assertNotNull(cancel);
        assertEquals("cancel-assist", cancel.getName());

        SopDefinition booking = catalog.select("BOOKING_DRAFT", false);
        assertNotNull(booking);
        assertEquals("booking-assist", booking.getName());

        SopDefinition knowledge = catalog.select("RAG_KNOWLEDGE", false);
        assertNotNull(knowledge);
        assertEquals("knowledge-consult", knowledge.getName());
    }

    @Test
    void 未知意图不选择SOP() {
        assertNull(catalog.select("UNSURE", false));
        assertNull(catalog.select(null, false));
    }

    @Test
    void SOP正文可加载且来自服务端资源() {
        SopDefinition cancel = catalog.select("BOOKING_CANCEL", false);
        String body = catalog.loadBody(cancel);

        assertTrue(body.contains("取消预约协助处理说明"));
        assertTrue(body.contains("只能查询本人预约、生成取消草稿，不能直接取消"));
    }

    @Test
    void SOP工具集合不包含未注册的高风险工具() {
        for (SopDefinition definition : catalog.select("BOOKING_CANCEL", false) == null
                ? java.util.List.<SopDefinition>of()
                : java.util.List.of(catalog.select("BOOKING_CANCEL", false))) {
            assertTrue(definition.getAllowedTools().stream()
                    .noneMatch(tool -> tool.equals("confirm_cancel_booking")
                            || tool.equals("cancel_booking")));
        }
    }
}

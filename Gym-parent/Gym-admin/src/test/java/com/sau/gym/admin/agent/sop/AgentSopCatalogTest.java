package com.sau.gym.admin.agent.sop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作者:hfj
 * 功能:客服 SOP 目录测试（R2 修复后语义）
 * 说明:
 * 覆盖：目录加载、按意图选择、组合场景多选、纯咨询不触发办理 SOP、
 * 待办草稿类型纳入判断、SOP 工具集不含真实执行类工具。
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
        assertEquals("1.1.0", catalog.getCatalogVersion());
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
        SopDefinition cancel = catalog.selectSops("BOOKING_CANCEL", null, null).get(0);
        String body = catalog.loadBody(cancel);

        assertTrue(body.contains("取消预约协助处理说明"));
        assertTrue(body.contains("只能查询本人预约、生成取消草稿，不能直接取消"));
    }

    @Test
    void 组合请求应命中咨询与商品两个SOP() {
        //“告诉我取消规则，顺便帮我买两瓶水”：
        //主意图是咨询，取消属于规则咨询不触发办理，但“买两瓶”是明确办理型子请求。
        List<SopDefinition> selected = catalog.selectSops(
                "RAG_KNOWLEDGE", "告诉我取消规则，顺便帮我买两瓶水", null);

        Set<String> names = new LinkedHashSet<>();
        selected.forEach(sop -> names.add(sop.getName()));

        assertTrue(names.contains("knowledge-consult"));
        assertTrue(names.contains("shopping-assist"), "明确办理型子请求必须保留对应 SOP");
        assertFalse(names.contains("cancel-assist"), "纯咨询措辞不应触发真实取消办理 SOP");
    }

    @Test
    void 明确要求查询可取消预约时应命中取消SOP() {
        List<SopDefinition> selected = catalog.selectSops(
                "RAG_KNOWLEDGE", "先解释退款规则，再查询我的可取消预约", null);

        Set<String> names = new LinkedHashSet<>();
        selected.forEach(sop -> names.add(sop.getName()));

        assertTrue(names.contains("knowledge-consult"));
        assertTrue(names.contains("cancel-assist"), "查询本人可取消预约是明确办理型请求");
    }

    @Test
    void 纯取消规则咨询不应命中取消办理SOP() {
        List<SopDefinition> selected = catalog.selectSops(
                "RAG_KNOWLEDGE", "取消预约有什么规则？", null);

        Set<String> names = new LinkedHashSet<>();
        selected.forEach(sop -> names.add(sop.getName()));

        assertTrue(names.contains("knowledge-consult"));
        assertFalse(names.contains("cancel-assist"), "纯咨询不能因为出现“取消”二字进入真实取消办理");
    }

    @Test
    void 待确认草稿类型应纳入场景判断() {
        List<SopDefinition> selected = catalog.selectSops(null, "继续", "SHOPPING");

        assertEquals(1, selected.size());
        assertEquals("shopping-assist", selected.get(0).getName());
    }

    @Test
    void 组合SOP的工具并集应覆盖两个子任务且不含真实执行类工具() {
        List<SopDefinition> selected = catalog.selectSops(
                "RAG_KNOWLEDGE", "告诉我取消规则，顺便帮我买两瓶水", null);

        Set<String> union = new LinkedHashSet<>();
        selected.forEach(sop -> union.addAll(sop.getAllowedTools()));

        assertTrue(union.contains("ask_gym_knowledge"), "咨询子任务工具必须保留");
        assertTrue(union.contains("create_shopping_draft"), "商品子任务工具必须保留");

        //真实确认/扣款/退款执行类工具继续不向模型开放。
        assertTrue(union.stream().noneMatch(tool ->
                tool.equals("confirm_booking")
                        || tool.equals("confirm_shopping")
                        || tool.equals("confirm_cancel_booking")));
    }
}

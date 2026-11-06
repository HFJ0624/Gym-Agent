package com.sau.gym.admin.rag.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作者:hfj
 * 功能:证据登记上下文测试（R5 修复）
 * 说明:
 * 覆盖：重复登记返回已有标签并回写新对象、部分重叠证据集合、
 * used 标记与来源一致性。
 */
class EvidenceRegistryTest {

    @Test
    void 重复登记同一evidenceId时新对象应获得已有标签() {
        EvidenceRegistry registry = new EvidenceRegistry();

        KnowledgeEvidence first = evidence("1:0:hash", "第一条");
        String label1 = registry.register(first);
        assertEquals("K1", label1);

        //第二次检索创建同一 evidenceId 的新对象。
        KnowledgeEvidence second = evidence("1:0:hash", "第一条");
        String label2 = registry.register(second);

        assertEquals("K1", label2, "重复登记应返回已有标签");
        assertEquals("K1", second.getCitationLabel(), "新对象也必须带规范标签，不能是 null");
        assertEquals(1, registry.size(), "不应产生重复证据");
        assertSame(first, registry.get("K1"), "登记表保留首个规范对象");
    }

    @Test
    void 部分重叠的两次检索应正确去重() {
        EvidenceRegistry registry = new EvidenceRegistry();

        KnowledgeEvidence a1 = evidence("doc1:0:h1", "A片段");
        KnowledgeEvidence b1 = evidence("doc2:0:h2", "B片段");
        registry.register(a1);
        registry.register(b1);

        //第二次检索返回 B(新对象) 与 C(新片段)。
        KnowledgeEvidence b2 = evidence("doc2:0:h2", "B片段");
        KnowledgeEvidence c = evidence("doc3:0:h3", "C片段");
        String bLabel = registry.register(b2);
        String cLabel = registry.register(c);

        assertEquals("K2", bLabel);
        assertEquals("K3", cLabel);
        assertEquals(3, registry.size());

        //新对象的标签可用且指向同一规范证据。
        assertEquals("K2", b2.getCitationLabel());
        assertSame(b1, registry.get("K2"));
    }

    @Test
    void 引用标记应只保留真实存在的标签() {
        EvidenceRegistry registry = new EvidenceRegistry();

        KnowledgeEvidence a = evidence("doc1:0:h1", "A片段");
        KnowledgeEvidence b = evidence("doc2:0:h2", "B片段");
        registry.register(a);
        registry.register(b);

        var used = registry.markUsed(java.util.List.of("K2", "K99", "K2"));

        assertEquals(1, used.size());
        assertEquals("doc2:0:h2", used.get(0).getEvidenceId());
    }

    @Test
    void 未使用时usedEvidences应为空() {
        EvidenceRegistry registry = new EvidenceRegistry();
        registry.register(evidence("doc1:0:h1", "A片段"));

        assertTrue(registry.usedEvidences().isEmpty());
    }

    private KnowledgeEvidence evidence(String evidenceId, String title) {
        KnowledgeEvidence evidence = new KnowledgeEvidence();
        evidence.setEvidenceId(evidenceId);
        evidence.setDocId(1L);
        evidence.setChunkIndex(0);
        evidence.setTitle(title);
        evidence.setText("内容");
        return evidence;
    }
}

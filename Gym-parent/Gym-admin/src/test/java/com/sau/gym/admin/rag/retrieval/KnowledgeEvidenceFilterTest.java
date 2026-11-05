package com.sau.gym.admin.rag.retrieval;

import com.sau.gym.model.entity.rag.KnowledgeDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 作者:hfj
 * 功能:知识证据过滤器测试
 * 说明:
 * 覆盖范围隔离、FAQ 非全局、禁用/删除、内容版本与旧向量兼容策略。
 */
class KnowledgeEvidenceFilterTest {

    private final KnowledgeEvidenceFilter filter = new KnowledgeEvidenceFilter();

    private Map<Long, KnowledgeDocument> documents;

    @BeforeEach
    void setUp() {
        documents = new HashMap<>();
        documents.put(1L, doc(1L, 2, 1L, null, true, false));
        documents.put(2L, doc(2L, 3, 1L, 10L, true, false));
        documents.put(3L, doc(3L, 3, 1L, 11L, true, false));
        documents.put(4L, doc(4L, 5, 1L, null, true, false));
        documents.put(5L, doc(5L, 5, null, null, true, false));
        documents.put(6L, doc(6L, 1, null, null, true, false));
        documents.put(7L, doc(7L, 2, 1L, null, false, false));
        documents.put(100L, doc(100L, 2, 99L, null, true, false));
    }

    @Test
    void A场馆问题不应召回B场馆资料() {
        ChunkCandidate otherVenue = candidate(100L, 0, 99L, null, 2, hashOf(100L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(otherVenue), 1L, null, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_SCOPE_MISMATCH, outcome.rejected.get(0).reason);
    }

    @Test
    void 同馆其他场地的专属资料不适用于当前场地() {
        ChunkCandidate otherCourt = candidate(3L, 0, 1L, 11L, 3, hashOf(3L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(otherCourt), 1L, 10L, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_SCOPE_MISMATCH, outcome.rejected.get(0).reason);
    }

    @Test
    void 绑定具体场馆的FAQ不能全局放行() {
        ChunkCandidate venueBoundFaq = candidate(4L, 0, 1L, null, 5, hashOf(4L));

        KnowledgeEvidenceFilter.FilterOutcome withoutVenue = filter.filter(
                List.of(venueBoundFaq), null, null, documents, true);

        assertEquals(1, withoutVenue.kept.size(), "未指定范围时不过滤");

        KnowledgeEvidenceFilter.FilterOutcome otherVenue = filter.filter(
                List.of(venueBoundFaq), 2L, null, documents, true);

        assertEquals(0, otherVenue.kept.size(), "FAQ 绑定场馆后不得在其他场馆使用");
    }

    @Test
    void 未绑定场馆的FAQ可以全局适用() {
        ChunkCandidate globalFaq = candidate(5L, 0, null, null, 5, hashOf(5L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(globalFaq), 1L, null, documents, true);

        assertEquals(1, outcome.kept.size());
    }

    @Test
    void 已禁用或已删除的文档不能作为证据() {
        ChunkCandidate disabled = candidate(7L, 0, 1L, null, 2, hashOf(7L));
        ChunkCandidate deleted = candidate(8L, 0, 1L, null, 2, hashOf(8L));

        documents.put(8L, doc(8L, 2, 1L, null, true, true));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(disabled, deleted), 1L, null, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_DOC_DISABLED, outcome.rejected.get(0).reason);
        assertEquals(KnowledgeEvidenceFilter.REJECT_DOC_DELETED, outcome.rejected.get(1).reason);
    }

    @Test
    void 向量不存在对应文档时应拒绝() {
        ChunkCandidate missing = candidate(999L, 0, 1L, null, 2, "any-hash");

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(missing), 1L, null, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_DOC_NOT_FOUND, outcome.rejected.get(0).reason);
    }

    @Test
    void 内容已更新的旧片段应被拒绝() {
        ChunkCandidate stale = candidate(1L, 0, 1L, null, 2, "old-hash");

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(stale), 1L, null, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_CONTENT_STALE, outcome.rejected.get(0).reason);
    }

    @Test
    void 严格模式下缺少指纹的旧向量应跳过() {
        ChunkCandidate legacy = candidate(1L, 0, 1L, null, 2, "");

        KnowledgeEvidenceFilter.FilterOutcome strict = filter.filter(
                List.of(legacy), 1L, null, documents, true);

        assertEquals(0, strict.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_VERSION_UNKNOWN, strict.rejected.get(0).reason);

        //非严格模式保留，但调用方必须标注版本未验证，不得当作已验证内容。
        KnowledgeEvidenceFilter.FilterOutcome lenient = filter.filter(
                List.of(legacy), 1L, null, documents, false);

        assertEquals(1, lenient.kept.size());
    }

    @Test
    void 内容一致的资料应保留() {
        ChunkCandidate venueDoc = candidate(1L, 0, 1L, null, 2, hashOf(1L));
        ChunkCandidate courtDoc = candidate(2L, 0, 1L, 10L, 3, hashOf(2L));
        ChunkCandidate platformDoc = candidate(6L, 0, null, null, 1, hashOf(6L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(venueDoc, courtDoc, platformDoc), 1L, 10L, documents, true);

        assertEquals(3, outcome.kept.size());
        assertTrue(outcome.rejected.isEmpty());
    }

    // ------------------------------------------------------------------
    // 构造辅助
    // ------------------------------------------------------------------

    private KnowledgeDocument doc(Long id, Integer scope, Long venueId, Long courtId,
                                  boolean enabled, boolean deleted) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(id);
        document.setTitle("文档" + id);
        document.setContent("内容" + id);
        document.setKnowledgeScope(scope);
        document.setVenueId(venueId);
        document.setVenueName(venueId == null ? null : "场馆" + venueId);
        document.setCourtId(courtId);
        document.setCourtName(courtId == null ? null : "场地" + courtId);
        document.setEnabled(enabled ? 1 : 0);
        document.setIsDeleted(deleted ? 1 : 0);
        return document;
    }

    /**
     * 构造候选片段，contentHash 传 null 表示缺少指纹。
     */
    private ChunkCandidate candidate(Long docId, int chunkIndex, Long venueId, Long courtId,
                                     Integer scope, String contentHash) {
        ChunkCandidate candidate = new ChunkCandidate();
        candidate.setDocId(docId);
        candidate.setChunkIndex(chunkIndex);
        candidate.setText("片段内容");
        candidate.setScore(0.8D);

        Map<String, String> meta = new HashMap<>();
        meta.put("docId", String.valueOf(docId));
        meta.put("chunkIndex", String.valueOf(chunkIndex));
        meta.put("title", "文档" + docId);
        meta.put("knowledgeScope", String.valueOf(scope));

        if (venueId != null) {
            meta.put("venueId", String.valueOf(venueId));
            meta.put("venueName", "场馆" + venueId);
        }

        if (courtId != null) {
            meta.put("courtId", String.valueOf(courtId));
            meta.put("courtName", "场地" + courtId);
        }

        meta.put("contentHash", contentHash == null ? "" : contentHash);

        candidate.setMetadata(meta);
        return candidate;
    }

    /**
     * 与权威文档一致的指纹。
     */
    private String hashOf(Long docId) {
        return KnowledgeFingerprints.fingerprint(documents.get(docId));
    }
}

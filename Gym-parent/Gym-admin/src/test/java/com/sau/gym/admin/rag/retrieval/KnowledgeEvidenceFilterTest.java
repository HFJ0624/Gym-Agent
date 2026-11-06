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
 * 功能:知识证据过滤器测试（R1 修复后语义）
 * 说明:
 * 覆盖：范围隔离、无范围模式、FAQ/公告绑定场地限制、平台级矛盾、
 * 禁用/删除、内容版本与旧向量兼容策略。
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
        //FAQ 绑定具体场地（同馆 11 号场地）
        documents.put(11L, doc(11L, 5, 1L, 11L, true, false));
        //公告绑定具体场馆
        documents.put(12L, doc(12L, 4, 2L, null, true, false));
        //“平台级”却绑定了场馆的矛盾资料
        documents.put(13L, doc(13L, 1, 2L, null, true, false));
    }

    @Test
    void A场馆问题不应召回B场馆资料() {
        ChunkCandidate otherVenue = candidate(100L, 0, hashOf(100L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(otherVenue), 1L, null, true, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_SCOPE_MISMATCH, outcome.rejected.get(0).reason);
    }

    @Test
    void 无范围时场馆级资料不应当作公共规则() {
        ChunkCandidate venueRule = candidate(1L, 0, hashOf(1L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(venueRule), null, null, false, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_SCOPE_REQUIRED, outcome.rejected.get(0).reason);
    }

    @Test
    void 无范围时只保留平台级与真正未绑定的FAQ公告() {
        ChunkCandidate platform = candidate(6L, 0, hashOf(6L));
        ChunkCandidate globalFaq = candidate(5L, 0, hashOf(5L));
        ChunkCandidate venueFaq = candidate(4L, 0, hashOf(4L));
        ChunkCandidate venueRule = candidate(1L, 0, hashOf(1L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(platform, globalFaq, venueFaq, venueRule),
                null, null, false, documents, true);

        assertEquals(2, outcome.kept.size());
        assertEquals(2, outcome.rejected.size());
        assertTrue(outcome.rejected.stream().allMatch(r ->
                KnowledgeEvidenceFilter.REJECT_SCOPE_REQUIRED.equals(r.reason)));
    }

    @Test
    void 同馆其他场地的专属FAQ不应适用于当前场地() {
        ChunkCandidate otherCourtFaq = candidate(11L, 0, hashOf(11L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(otherCourtFaq), 1L, 12L, true, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_SCOPE_MISMATCH, outcome.rejected.get(0).reason);
    }

    @Test
    void 绑定场地的FAQ只对该场地适用() {
        ChunkCandidate courtFaq = candidate(11L, 0, hashOf(11L));

        KnowledgeEvidenceFilter.FilterOutcome venueOnly = filter.filter(
                List.of(courtFaq), 1L, null, true, documents, true);

        assertEquals(0, venueOnly.kept.size(), "未指定具体场地时不能当作整个场馆的通用规则");
        assertEquals(KnowledgeEvidenceFilter.REJECT_SCOPE_MISMATCH, venueOnly.rejected.get(0).reason);

        KnowledgeEvidenceFilter.FilterOutcome exactCourt = filter.filter(
                List.of(courtFaq), 1L, 11L, true, documents, true);

        assertEquals(1, exactCourt.kept.size());
    }

    @Test
    void 绑定其他场馆的公告不应适用() {
        ChunkCandidate otherVenueNotice = candidate(12L, 0, hashOf(12L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(otherVenueNotice), 1L, null, true, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_SCOPE_MISMATCH, outcome.rejected.get(0).reason);
    }

    @Test
    void 场地资料与请求场馆不一致时应拒绝() {
        //场地 2 属于场馆 1；若上游归属校验被绕过，过滤器仍按权威文档字段拒绝。
        ChunkCandidate wrongAttribution = candidate(2L, 0, hashOf(2L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(wrongAttribution), 2L, 10L, true, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_SCOPE_MISMATCH, outcome.rejected.get(0).reason);
    }

    @Test
    void 平台级资料绑定了场馆属于矛盾应拒绝() {
        ChunkCandidate contradictory = candidate(13L, 0, hashOf(13L));

        KnowledgeEvidenceFilter.FilterOutcome unscoped = filter.filter(
                List.of(contradictory), null, null, false, documents, true);
        assertEquals(0, unscoped.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_SCOPE_CONTRADICTION, unscoped.rejected.get(0).reason);

        KnowledgeEvidenceFilter.FilterOutcome scoped = filter.filter(
                List.of(contradictory), 2L, null, true, documents, true);
        assertEquals(0, scoped.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_SCOPE_CONTRADICTION, scoped.rejected.get(0).reason);
    }

    @Test
    void 范围完全匹配的资料应全部保留() {
        ChunkCandidate venueDoc = candidate(1L, 0, hashOf(1L));
        ChunkCandidate courtDoc = candidate(2L, 0, hashOf(2L));
        ChunkCandidate platformDoc = candidate(6L, 0, hashOf(6L));
        ChunkCandidate venueFaq = candidate(4L, 0, hashOf(4L));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(venueDoc, courtDoc, platformDoc, venueFaq), 1L, 10L, true, documents, true);

        assertEquals(4, outcome.kept.size());
        assertTrue(outcome.rejected.isEmpty());
    }

    @Test
    void 已禁用或已删除的文档不能作为证据() {
        ChunkCandidate disabled = candidate(7L, 0, hashOf(7L));
        ChunkCandidate deleted = candidate(8L, 0, hashOf(8L));

        documents.put(8L, doc(8L, 2, 1L, null, true, true));

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(disabled, deleted), 1L, null, true, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_DOC_DISABLED, outcome.rejected.get(0).reason);
        assertEquals(KnowledgeEvidenceFilter.REJECT_DOC_DELETED, outcome.rejected.get(1).reason);
    }

    @Test
    void 向量不存在对应文档时应拒绝() {
        ChunkCandidate missing = candidate(999L, 0, "any-hash");

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(missing), 1L, null, true, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_DOC_NOT_FOUND, outcome.rejected.get(0).reason);
    }

    @Test
    void 内容已更新的旧片段应被拒绝() {
        ChunkCandidate stale = candidate(1L, 0, "old-hash");

        KnowledgeEvidenceFilter.FilterOutcome outcome = filter.filter(
                List.of(stale), 1L, null, true, documents, true);

        assertEquals(0, outcome.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_CONTENT_STALE, outcome.rejected.get(0).reason);
    }

    @Test
    void 严格模式下缺少指纹的旧向量应跳过() {
        ChunkCandidate legacy = candidate(1L, 0, "");

        KnowledgeEvidenceFilter.FilterOutcome strict = filter.filter(
                List.of(legacy), 1L, null, true, documents, true);

        assertEquals(0, strict.kept.size());
        assertEquals(KnowledgeEvidenceFilter.REJECT_VERSION_UNKNOWN, strict.rejected.get(0).reason);

        //非严格模式保留，但调用方必须标注版本未验证，不得当作已验证内容。
        KnowledgeEvidenceFilter.FilterOutcome lenient = filter.filter(
                List.of(legacy), 1L, null, true, documents, false);

        assertEquals(1, lenient.kept.size());
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
    private ChunkCandidate candidate(Long docId, int chunkIndex, String contentHash) {
        ChunkCandidate candidate = new ChunkCandidate();
        candidate.setDocId(docId);
        candidate.setChunkIndex(chunkIndex);
        candidate.setText("片段内容");
        candidate.setScore(0.8D);

        Map<String, String> meta = new HashMap<>();
        meta.put("docId", String.valueOf(docId));
        meta.put("chunkIndex", String.valueOf(chunkIndex));
        meta.put("title", "文档" + docId);
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

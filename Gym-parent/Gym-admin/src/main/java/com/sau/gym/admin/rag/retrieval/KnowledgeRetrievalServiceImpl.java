package com.sau.gym.admin.rag.retrieval;

import com.sau.gym.admin.agent.service.AgentTraceService;
import com.sau.gym.admin.mapper.KnowledgeDocumentMapper;
import com.sau.gym.model.entity.rag.KnowledgeDocument;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 作者:hfj
 * 功能:知识纯检索服务实现
 * 说明:
 * 1. 只做 Embedding、向量检索、范围/有效性过滤、去重和证据组装。
 * 2. 绝不调用生成式 Chat 模型，也不调用 RagQaService.ask()。
 * 3. Embedding 阶段与检索阶段耗时分别记录，失败状态分开。
 */
@Service
public class KnowledgeRetrievalServiceImpl implements KnowledgeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalServiceImpl.class);

    /**
     * RAG 专用 embedding 模型。
     */
    @Resource(name = "ragEmbeddingModel")
    private EmbeddingModel ragEmbeddingModel;

    private final EmbeddingStore<TextSegment> ragEmbeddingStore;

    private final KnowledgeDocumentMapper documentMapper;

    private final KnowledgeEvidenceFilter evidenceFilter;

    private final AgentTraceService agentTraceService;

    /**
     * 默认返回证据数。
     */
    @Value("${gym.rag.retrieval.max-results:4}")
    private Integer defaultTopK;

    /**
     * 默认相关性阈值。
     */
    @Value("${gym.rag.retrieval.min-score:0.50}")
    private Double defaultMinScore;

    /**
     * 候选扩大倍数。
     * 向量检索阶段先取 topK * 倍数 个候选，再做范围/有效性过滤，
     * 避免只取很小的 TopK 后过滤导致“假装召回充分”。
     */
    @Value("${gym.rag.retrieval.candidate-multiplier:5}")
    private Integer candidateMultiplier;

    /**
     * 是否严格校验内容版本。
     * 严格模式下，缺少指纹的旧向量会被跳过，并提示执行知识库重建。
     */
    @Value("${gym.rag.retrieval.strict-version:true}")
    private boolean strictVersion;

    public KnowledgeRetrievalServiceImpl(EmbeddingStore<TextSegment> ragEmbeddingStore,
                                         KnowledgeDocumentMapper documentMapper,
                                         KnowledgeEvidenceFilter evidenceFilter,
                                         AgentTraceService agentTraceService) {
        this.ragEmbeddingStore = ragEmbeddingStore;
        this.documentMapper = documentMapper;
        this.evidenceFilter = evidenceFilter;
        this.agentTraceService = agentTraceService;
    }

    @Override
    public KnowledgeRetrievalResult retrieve(KnowledgeRetrievalRequest request) {
        long start = System.currentTimeMillis();

        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult();

        List<Long> compareVenueIds = request == null ? null : request.getCompareVenueIds();
        boolean compareMode = compareVenueIds != null && !compareVenueIds.isEmpty();
        boolean scoped = compareMode
                || (request != null && (request.getVenueId() != null || request.getCourtId() != null));

        result.setScoped(scoped);

        int topK = resolveTopK(request);
        double minScore = resolveMinScore(request);

        try {
            //1. 向量化阶段，独立计时，失败单独标记。
            long embedStart = System.currentTimeMillis();
            Embedding queryEmbedding = ragEmbeddingModel.embed(request.getQuestion()).content();
            result.setEmbeddingMs(System.currentTimeMillis() - embedStart);

            //2. 向量检索阶段：先按倍数扩大候选，再过滤，保证过滤后仍有充足证据。
            long searchStart = System.currentTimeMillis();
            int candidateLimit = Math.max(topK * resolveMultiplier(), topK);

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(candidateLimit)
                    .minScore(minScore)
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = ragEmbeddingStore.search(searchRequest);
            result.setSearchMs(System.currentTimeMillis() - searchStart);

            List<EmbeddingMatch<TextSegment>> matches = searchResult == null
                    ? List.of()
                    : searchResult.matches();

            List<ChunkCandidate> candidates = toCandidates(matches);
            result.setCandidateCount(candidates.size());

            if (candidates.isEmpty()) {
                result.setStatus(RetrievalStatus.NO_EVIDENCE);
                result.setDetail("向量检索没有返回任何满足阈值的候选。");
                finishTrace(result, request, start);
                return result;
            }

            //3. 批量加载权威文档，做启用/删除/内容版本校验。
            Map<Long, KnowledgeDocument> documents = loadDocuments(candidates);

            //4. 范围与有效性过滤。
            //COMPARE 模式：每个场馆分别做范围过滤并保留归属，各自截取 topK 后合并；
            //它不是无范围放行的理由。
            List<KnowledgeEvidence> evidences = new ArrayList<>();

            if (compareMode) {
                Map<Long, KnowledgeEvidenceFilter.FilterOutcome> outcomes = new LinkedHashMap<>();

                for (Long venueId : compareVenueIds) {
                    KnowledgeEvidenceFilter.FilterOutcome outcome = evidenceFilter.filter(
                            candidates, venueId, null, true, documents, strictVersion);
                    outcomes.put(venueId, outcome);
                    result.setRejectedCount(result.getRejectedCount() + outcome.rejected.size());

                    List<KnowledgeEvidence> venueEvidences =
                            buildEvidences(outcome.kept, documents, topK);
                    evidences.addAll(venueEvidences);
                }

                evidences.sort(Comparator.comparing(KnowledgeEvidence::getScore,
                        Comparator.nullsLast(Comparator.reverseOrder())));

                StringBuilder detail = new StringBuilder("COMPARE 模式，按场馆分别过滤：");
                outcomes.forEach((venueId, outcome) -> detail.append(" venue")
                        .append(venueId).append("=kept").append(outcome.kept.size()).append(" "));

                result.setStatus(evidences.isEmpty()
                        ? RetrievalStatus.INVALID_SCOPE
                        : RetrievalStatus.HIT);
                result.setDetail(detail.toString().trim());
                result.setEvidences(evidences);

                finishTrace(result, request, start);
                return result;
            }

            KnowledgeEvidenceFilter.FilterOutcome outcome = evidenceFilter.filter(
                    candidates,
                    request.getVenueId(),
                    request.getCourtId(),
                    scoped,
                    documents,
                    strictVersion
            );
            result.setRejectedCount(outcome.rejected.size());

            //5. 去重、排序、截断到 topK，组装证据。
            evidences = buildEvidences(outcome.kept, documents, topK);
            result.setEvidences(evidences);

            if (evidences.isEmpty()) {
                result.setStatus(result.isScoped()
                        ? RetrievalStatus.INVALID_SCOPE
                        : RetrievalStatus.NO_EVIDENCE);
                result.setDetail(buildRejectionDetail(outcome));
            } else {
                result.setStatus(RetrievalStatus.HIT);
                result.setDetail(buildRejectionDetail(outcome));
            }

            finishTrace(result, request, start);
            return result;

        } catch (Exception e) {
            //检索异常不能伪装成“无资料”。
            log.warn("[KnowledgeRetrieval] 检索失败: {}", e.getMessage());

            result.setStatus(RetrievalStatus.SEARCH_FAILED);
            result.setDetail("知识检索失败：" + e.getMessage());
            result.setElapsedMs(System.currentTimeMillis() - start);

            finishTrace(result, request, start);
            return result;
        }
    }

    /**
     * 把向量匹配结果转换为纯 Java 候选。
     */
    private List<ChunkCandidate> toCandidates(List<EmbeddingMatch<TextSegment>> matches) {
        List<ChunkCandidate> candidates = new ArrayList<>();

        if (matches == null) {
            return candidates;
        }

        for (EmbeddingMatch<TextSegment> match : matches) {
            if (match == null || match.embedded() == null) {
                continue;
            }

            TextSegment segment = match.embedded();
            Metadata metadata = segment.metadata();

            Map<String, String> snapshot = new LinkedHashMap<>();
            for (String key : List.of(
                    "docId", "chunkIndex", "title", "contentHash", "knowledgeScope",
                    "sourceType", "venueId", "venueName", "courtId", "courtName",
                    "courtType", "topic", "tags", "priority")) {
                snapshot.put(key, metadata.getString(key));
            }

            ChunkCandidate candidate = new ChunkCandidate();
            candidate.setDocId(parseLong(snapshot.get("docId")));
            candidate.setChunkIndex(parseInteger(snapshot.get("chunkIndex")));
            candidate.setText(segment.text());
            candidate.setScore(match.score());
            candidate.setMetadata(snapshot);

            candidates.add(candidate);
        }

        return candidates;
    }

    /**
     * 批量加载权威文档。
     */
    private Map<Long, KnowledgeDocument> loadDocuments(List<ChunkCandidate> candidates) {
        Set<Long> docIds = candidates.stream()
                .map(ChunkCandidate::getDocId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        if (docIds.isEmpty()) {
            return Map.of();
        }

        List<KnowledgeDocument> documents = documentMapper.selectByIds(new ArrayList<>(docIds));

        Map<Long, KnowledgeDocument> result = new LinkedHashMap<>();
        if (documents != null) {
            documents.forEach(document -> result.put(document.getId(), document));
        }

        return result;
    }

    /**
     * 组装证据：按 docId+chunkIndex 去重，按分数排序，限制 topK。
     */
    private List<KnowledgeEvidence> buildEvidences(List<ChunkCandidate> kept,
                                                   Map<Long, KnowledgeDocument> documents,
                                                   int topK) {
        List<ChunkCandidate> sorted = new ArrayList<>(kept);
        sorted.sort(Comparator.comparing(ChunkCandidate::getScore,
                Comparator.nullsLast(Comparator.reverseOrder())));

        List<KnowledgeEvidence> evidences = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (ChunkCandidate candidate : sorted) {
            if (evidences.size() >= topK) {
                break;
            }

            String dedupeKey = candidate.getDocId() + ":" + candidate.getChunkIndex();
            if (!seen.add(dedupeKey)) {
                continue;
            }

            KnowledgeDocument document = documents.get(candidate.getDocId());
            Map<String, String> metadata = candidate.getMetadata();

            KnowledgeEvidence evidence = new KnowledgeEvidence();
            evidence.setDocId(candidate.getDocId());
            evidence.setChunkIndex(candidate.getChunkIndex());

            //evidenceId = docId:chunkIndex:内容指纹前缀，长期稳定，不依赖本次列表位置。
            String hashPrefix = StringUtils.hasText(metadata.get("contentHash"))
                    ? metadata.get("contentHash").substring(0, Math.min(16, metadata.get("contentHash").length()))
                    : "unknown";
            evidence.setEvidenceId(dedupeKey + ":" + hashPrefix);

            evidence.setTitle(metadata.get("title"));
            evidence.setText(candidate.getText());
            evidence.setVenueId(parseLong(metadata.get("venueId")));
            evidence.setVenueName(metadata.get("venueName"));
            evidence.setCourtId(parseLong(metadata.get("courtId")));
            evidence.setCourtName(metadata.get("courtName"));
            evidence.setKnowledgeScope(parseInteger(metadata.get("knowledgeScope")));
            evidence.setKnowledgeScopeName(scopeName(evidence.getKnowledgeScope()));
            evidence.setSourceType(parseInteger(metadata.get("sourceType")));
            evidence.setTopic(metadata.get("topic"));
            evidence.setTags(metadata.get("tags"));
            evidence.setScore(candidate.getScore());
            evidence.setContentHash(metadata.get("contentHash"));
            evidence.setUpdateTime(document == null || document.getUpdateTime() == null
                    ? null
                    : String.valueOf(document.getUpdateTime()));

            evidences.add(evidence);
        }

        return evidences;
    }

    /**
     * 汇总拒绝原因。
     * 存在旧版本数据时提示执行既有重建入口，不自动清空或批量删除。
     */
    private String buildRejectionDetail(KnowledgeEvidenceFilter.FilterOutcome outcome) {
        if (outcome.rejected.isEmpty()) {
            return null;
        }

        Map<String, Long> reasons = outcome.rejected.stream()
                .collect(Collectors.groupingBy(item -> item.reason, Collectors.counting()));

        StringBuilder builder = new StringBuilder("过滤拒绝统计：");
        reasons.forEach((reason, count) -> builder.append(reason).append("=").append(count).append(" "));

        if (reasons.containsKey(KnowledgeEvidenceFilter.REJECT_VERSION_UNKNOWN)) {
            builder.append("。存在缺少内容指纹的旧向量，请通过既有知识库重建入口重新索引后再启用严格校验。");
        }

        return builder.toString().trim();
    }

    /**
     * 写 Trace，未处于 Agent 运行中时为空操作。
     */
    private void finishTrace(KnowledgeRetrievalResult result,
                             KnowledgeRetrievalRequest request,
                             long start) {
        result.setElapsedMs(System.currentTimeMillis() - start);

        try {
            agentTraceService.addStep(
                    "KNOWLEDGE_RETRIEVE",
                    "知识检索: " + result.getStatus(),
                    "venueId=" + (request == null ? null : request.getVenueId())
                            + ", courtId=" + (request == null ? null : request.getCourtId())
                            + ", topK=" + resolveTopK(request),
                    "candidates=" + result.getCandidateCount()
                            + ", kept=" + result.getEvidences().size()
                            + ", rejected=" + result.getRejectedCount()
                            + ", embeddingMs=" + result.getEmbeddingMs()
                            + ", searchMs=" + result.getSearchMs()
                            + (result.getDetail() == null ? "" : ", detail=" + result.getDetail()),
                    result.getStatus() == RetrievalStatus.SEARCH_FAILED
                            || result.getStatus() == RetrievalStatus.EMBEDDING_FAILED ? "FAILED" : "SUCCESS",
                    result.getStatus() == RetrievalStatus.SEARCH_FAILED
                            || result.getStatus() == RetrievalStatus.EMBEDDING_FAILED
                            ? result.getDetail() : null,
                    result.getElapsedMs()
            );
        } catch (Exception e) {
            log.warn("[KnowledgeRetrieval] Trace 写入失败: {}", e.getMessage());
        }
    }

    private int resolveTopK(KnowledgeRetrievalRequest request) {
        if (request != null && request.getTopK() != null && request.getTopK() > 0) {
            return request.getTopK();
        }

        return defaultTopK != null && defaultTopK > 0 ? defaultTopK : 4;
    }

    private double resolveMinScore(KnowledgeRetrievalRequest request) {
        if (request != null && request.getMinScore() != null) {
            return request.getMinScore();
        }

        return defaultMinScore != null ? defaultMinScore : 0.50D;
    }

    private int resolveMultiplier() {
        return candidateMultiplier != null && candidateMultiplier > 0 ? candidateMultiplier : 5;
    }

    private String scopeName(Integer scope) {
        if (scope == null) {
            return "未知";
        }

        return switch (scope) {
            case 1 -> "平台级知识";
            case 2 -> "场馆级知识";
            case 3 -> "场地级知识";
            case 4 -> "公告级知识";
            case 5 -> "常见问题";
            default -> "未知";
        };
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        try {
            return Long.valueOf(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        try {
            return Integer.valueOf(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}

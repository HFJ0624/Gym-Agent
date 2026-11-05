package com.sau.gym.admin.rag.retrieval;

import com.sau.gym.model.entity.rag.KnowledgeDocument;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:知识证据过滤器
 * 说明:
 * 纯逻辑组件，便于单元测试。职责：
 * 1. 适用范围校验（平台/场馆/场地/公告/FAQ）。
 * 2. 权威文档校验：不存在、已删除、已禁用。
 * 3. 内容版本校验：向量 metadata 指纹与权威文档当前指纹比对。
 */
@Component
public class KnowledgeEvidenceFilter {

    /**
     * 拒绝原因常量。
     */
    public static final String REJECT_DOC_NOT_FOUND = "DOC_NOT_FOUND";
    public static final String REJECT_DOC_DELETED = "DOC_DELETED";
    public static final String REJECT_DOC_DISABLED = "DOC_DISABLED";
    public static final String REJECT_SCOPE_MISMATCH = "SCOPE_MISMATCH";
    public static final String REJECT_CONTENT_STALE = "CONTENT_STALE";
    public static final String REJECT_VERSION_UNKNOWN = "VERSION_UNKNOWN";

    /**
     * 过滤结果。
     */
    public static class FilterOutcome {
        public final List<ChunkCandidate> kept = new ArrayList<>();
        public final List<Rejected> rejected = new ArrayList<>();
    }

    /**
     * 被拒绝的候选。
     */
    public static class Rejected {
        public final ChunkCandidate candidate;
        public final String reason;

        public Rejected(ChunkCandidate candidate, String reason) {
            this.candidate = candidate;
            this.reason = reason;
        }
    }

    /**
     * 执行过滤。
     *
     * @param candidates    向量检索候选
     * @param venueId       请求场馆ID，可为空
     * @param courtId       请求场地ID，可为空
     * @param documents     权威文档快照，key 为 docId
     * @param strictVersion 是否严格校验内容版本
     * @return 过滤结果
     */
    public FilterOutcome filter(List<ChunkCandidate> candidates,
                                Long venueId,
                                Long courtId,
                                Map<Long, KnowledgeDocument> documents,
                                boolean strictVersion) {
        FilterOutcome outcome = new FilterOutcome();

        if (candidates == null || candidates.isEmpty()) {
            return outcome;
        }

        boolean scoped = venueId != null || courtId != null;

        for (ChunkCandidate candidate : candidates) {
            if (candidate == null || candidate.getDocId() == null) {
                outcome.rejected.add(new Rejected(candidate, REJECT_DOC_NOT_FOUND));
                continue;
            }

            KnowledgeDocument document = documents.get(candidate.getDocId());

            //权威文档不存在，说明向量已删除或脏数据。
            if (document == null) {
                outcome.rejected.add(new Rejected(candidate, REJECT_DOC_NOT_FOUND));
                continue;
            }

            //已删除的知识不能再作为证据。
            if (document.getIsDeleted() != null && document.getIsDeleted() == 1) {
                outcome.rejected.add(new Rejected(candidate, REJECT_DOC_DELETED));
                continue;
            }

            //已禁用的知识不能再作为证据。
            if (document.getEnabled() != null && document.getEnabled() != 1) {
                outcome.rejected.add(new Rejected(candidate, REJECT_DOC_DISABLED));
                continue;
            }

            //内容版本校验。updateTime 不参与判定，避免刚建好的索引被误判过期。
            String metadataHash = candidate.getMetadata().get("contentHash");
            String currentHash = KnowledgeFingerprints.fingerprint(document);

            if (!StringUtils.hasText(metadataHash)) {
                if (strictVersion) {
                    outcome.rejected.add(new Rejected(candidate, REJECT_VERSION_UNKNOWN));
                    continue;
                }
            } else if (!metadataHash.equals(currentHash)) {
                outcome.rejected.add(new Rejected(candidate, REJECT_CONTENT_STALE));
                continue;
            }

            //适用范围校验。
            if (scoped && !matchesScope(candidate.getMetadata(), venueId, courtId)) {
                outcome.rejected.add(new Rejected(candidate, REJECT_SCOPE_MISMATCH));
                continue;
            }

            outcome.kept.add(candidate);
        }

        return outcome;
    }

    /**
     * 适用范围判定。
     * 规则与第一轮保持一致并收紧：
     * 1. 平台级（1）全局适用。
     * 2. 场馆级（2）必须与当前场馆一致。
     * 3. 场地级（3）必须与当前场地一致；同时传入场馆时校验归属关系。
     * 4. 公告级（4）未绑定场馆或绑定当前场馆。
     * 5. FAQ（5）不等于全局适用，必须未绑定场馆或绑定当前场馆。
     * 6. 无范围元数据时无法确认适用，指定范围时排除。
     */
    private boolean matchesScope(Map<String, String> metadata, Long venueId, Long courtId) {
        Long metadataVenueId = parseLong(metadata.get("venueId"));
        Long metadataCourtId = parseLong(metadata.get("courtId"));
        Integer scope = parseInteger(metadata.get("knowledgeScope"));

        if (scope == null) {
            return false;
        }

        boolean hasVenue = venueId != null;
        boolean hasCourt = courtId != null;

        return switch (scope) {
            case 1 -> true;
            case 2 -> hasVenue && venueId.equals(metadataVenueId);
            case 3 -> {
                //场地级资料必须命中当前场地；同时传入场馆时必须归属同一场馆。
                if (!hasCourt || !courtId.equals(metadataCourtId)) {
                    yield false;
                }
                yield !hasVenue || venueId.equals(metadataVenueId);
            }
            case 4 -> metadataVenueId == null || (hasVenue && venueId.equals(metadataVenueId));
            case 5 -> metadataVenueId == null || (hasVenue && venueId.equals(metadataVenueId));
            default -> false;
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

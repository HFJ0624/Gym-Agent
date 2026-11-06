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
 * 1. 适用范围校验：范围判定使用权威文档字段（knowledgeScope/venueId/courtId），
 *    不再用向量 metadata 的数字代替权威归属。
 * 2. 查询模式：SCOPED（明确了场馆/场地）与 UNSCOPED（两者都没有）。
 *    UNSCOPED 只放行平台级资料和真正未绑定对象的 FAQ/公告，
 *    不把任意场馆规则当作公共规则；跨场馆比较必须走 COMPARE 模式分别限定范围。
 * 3. 绑定限制应用于 FAQ、公告等所有分类：绑定 courtId 的资料只对该场地适用，
 *    不能当作整个场馆的通用规则。
 * 4. 范围与绑定字段矛盾时（如“平台级”却绑定了场馆）明确拒绝。
 * 5. 权威文档校验：不存在、已删除、已禁用。
 * 6. 内容版本校验：向量 metadata 指纹与权威文档当前指纹比对。
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
    public static final String REJECT_SCOPE_REQUIRED = "SCOPE_REQUIRED";
    public static final String REJECT_SCOPE_CONTRADICTION = "SCOPE_CONTRADICTION";
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
     * @param venueId       解析后的场馆ID，可为空
     * @param courtId       解析后的场地ID，可为空
     * @param scoped        是否明确了范围（venue/court 任一存在即为 true）
     * @param documents     权威文档快照，key 为 docId
     * @param strictVersion 是否严格校验内容版本
     */
    public FilterOutcome filter(List<ChunkCandidate> candidates,
                                Long venueId,
                                Long courtId,
                                boolean scoped,
                                Map<Long, KnowledgeDocument> documents,
                                boolean strictVersion) {
        FilterOutcome outcome = new FilterOutcome();

        if (candidates == null || candidates.isEmpty()) {
            return outcome;
        }

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

            //适用范围校验（基于权威文档字段）。
            String scopeReason = checkScope(document, venueId, courtId, scoped);

            if (scopeReason != null) {
                outcome.rejected.add(new Rejected(candidate, scopeReason));
                continue;
            }

            outcome.kept.add(candidate);
        }

        return outcome;
    }

    /**
     * 适用范围判定。
     * 返回 null 表示保留，否则返回拒绝原因。
     */
    private String checkScope(KnowledgeDocument document, Long venueId, Long courtId, boolean scoped) {
        Integer scope = document.getKnowledgeScope();
        Long docVenueId = document.getVenueId();
        Long docCourtId = document.getCourtId();

        if (scope == null) {
            return REJECT_SCOPE_CONTRADICTION;
        }

        return switch (scope) {
            //平台级：必须没有绑定任何场馆/场地，否则属于资料矛盾。
            case 1 -> (docVenueId != null || docCourtId != null)
                    ? REJECT_SCOPE_CONTRADICTION
                    : null;

            //场馆级：必须绑定场馆，且只在明确该场馆时适用。
            case 2 -> {
                if (docVenueId == null) {
                    yield REJECT_SCOPE_CONTRADICTION;
                }
                yield (scoped && venueId != null && venueId.equals(docVenueId))
                        ? null
                        : (scoped ? REJECT_SCOPE_MISMATCH : REJECT_SCOPE_REQUIRED);
            }

            //场地级：必须绑定场地，且只在明确该场地时适用；同时校验场馆归属。
            case 3 -> {
                if (docCourtId == null) {
                    yield REJECT_SCOPE_CONTRADICTION;
                }
                boolean courtMatch = scoped && courtId != null && courtId.equals(docCourtId);
                boolean venueMatch = docVenueId == null
                        || (scoped && venueId != null && venueId.equals(docVenueId));
                yield (courtMatch && venueMatch)
                        ? null
                        : (scoped ? REJECT_SCOPE_MISMATCH : REJECT_SCOPE_REQUIRED);
            }

            //公告级与 FAQ：绑定限制同样生效。
            //绑定场地：只对该场地适用，未指定具体场地时不能当作整个场馆的通用规则。
            //绑定场馆：只对该场馆适用。
            //完全未绑定：真正全局适用。
            case 4, 5 -> {
                if (docCourtId != null) {
                    boolean courtMatch = scoped && courtId != null && courtId.equals(docCourtId);
                    boolean venueMatch = docVenueId == null
                            || (scoped && venueId != null && venueId.equals(docVenueId));
                    yield (courtMatch && venueMatch)
                            ? null
                            : (scoped ? REJECT_SCOPE_MISMATCH : REJECT_SCOPE_REQUIRED);
                }

                if (docVenueId != null) {
                    yield (scoped && venueId != null && venueId.equals(docVenueId))
                            ? null
                            : (scoped ? REJECT_SCOPE_MISMATCH : REJECT_SCOPE_REQUIRED);
                }

                yield null;
            }

            default -> REJECT_SCOPE_CONTRADICTION;
        };
    }
}

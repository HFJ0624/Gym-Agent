package com.sau.gym.admin.rag.service.impl;

import com.sau.gym.admin.rag.retrieval.KnowledgeEvidence;
import com.sau.gym.admin.rag.retrieval.KnowledgeRetrievalRequest;
import com.sau.gym.admin.rag.retrieval.KnowledgeRetrievalResult;
import com.sau.gym.admin.rag.retrieval.KnowledgeRetrievalService;
import com.sau.gym.admin.rag.retrieval.RetrievalStatus;
import com.sau.gym.admin.rag.service.RagEvalSearchService;
import com.sau.gym.model.entity.rag.RagEvalSearchResult;
import com.sau.gym.model.vo.rag.RagSourceVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 作者:hfj
 * 功能:RAG 检索评估服务实现
 * 说明:
 * 第二轮起评估只调用纯检索服务：
 * 1. topK、minScore 真实作用于本次向量检索，不再事后裁剪冒充不同配置。
 * 2. 不调用任何生成模型，answer 恒为空；生成质量评测走独立路径另行实现。
 * 3. “检索是否命中”与“最终回答是否正确”就此分开。
 * 日期: 2026/5/9 19:32
 */
@Service
public class RagEvalSearchServiceImpl implements RagEvalSearchService {

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public RagEvalSearchServiceImpl(KnowledgeRetrievalService knowledgeRetrievalService) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    @Override
    public RagEvalSearchResult search(String question, Integer topK, Double minScore) {
        RagEvalSearchResult result = new RagEvalSearchResult();

        //1. 纯检索，评估参数直接传入检索服务并真实生效。
        KnowledgeRetrievalResult retrieval = knowledgeRetrievalService.retrieve(
                KnowledgeRetrievalRequest.builder()
                        .question(question)
                        .topK(topK)
                        .minScore(minScore)
                        .build()
        );

        //2. 纯检索评测不生成回答。
        result.setAnswer("");

        //3. 检索失败时来源为空，由评测层按未命中统计，而不是伪装成无答案用例。
        if (retrieval.getStatus() == RetrievalStatus.EMBEDDING_FAILED
                || retrieval.getStatus() == RetrievalStatus.SEARCH_FAILED) {
            result.setSources(new ArrayList<>());
            return result;
        }

        //4. 证据转来源视图。
        List<RagSourceVO> sources = new ArrayList<>();
        if (retrieval.getEvidences() != null) {
            for (KnowledgeEvidence evidence : retrieval.getEvidences()) {
                RagSourceVO source = new RagSourceVO();
                source.setDocId(evidence.getDocId());
                source.setTitle(evidence.getTitle());
                source.setKnowledgeScope(evidence.getKnowledgeScope());
                source.setKnowledgeScopeName(evidence.getKnowledgeScopeName());
                source.setSourceType(evidence.getSourceType());
                source.setVenueId(evidence.getVenueId());
                source.setVenueName(evidence.getVenueName());
                source.setCourtId(evidence.getCourtId());
                source.setCourtName(evidence.getCourtName());
                source.setTopic(evidence.getTopic());
                source.setTags(evidence.getTags());
                source.setScore(evidence.getScore());
                source.setContentPreview(preview(evidence.getText(), 120));
                sources.add(source);
            }
        }

        result.setSources(sources);

        //5. 相似度统计。
        result.setMaxScore(sources.stream()
                .map(RagSourceVO::getScore)
                .filter(score -> score != null)
                .max(Comparator.naturalOrder())
                .orElse(null));

        result.setMinScore(sources.stream()
                .map(RagSourceVO::getScore)
                .filter(score -> score != null)
                .min(Comparator.naturalOrder())
                .orElse(null));

        return result;
    }

    /**
     * 文本预览。
     */
    private String preview(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        String value = text.replaceAll("\\s+", " ").trim();

        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}

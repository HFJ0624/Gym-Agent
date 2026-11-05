package com.sau.gym.admin.rag.service.impl;

import com.alibaba.fastjson.JSON;
import com.sau.gym.admin.agent.llm.LlmClient;
import com.sau.gym.admin.agent.trace.AgentTraceContext;
import com.sau.gym.admin.mapper.RagSearchLogMapper;
import com.sau.gym.admin.rag.answer.KnowledgeAnswerComposer;
import com.sau.gym.admin.rag.retrieval.KnowledgeEvidence;
import com.sau.gym.admin.rag.retrieval.KnowledgeRetrievalRequest;
import com.sau.gym.admin.rag.retrieval.KnowledgeRetrievalResult;
import com.sau.gym.admin.rag.retrieval.KnowledgeRetrievalService;
import com.sau.gym.admin.rag.retrieval.RetrievalStatus;
import com.sau.gym.admin.rag.service.RagQaService;
import com.sau.gym.model.dto.rag.RagAskDto;
import com.sau.gym.model.entity.rag.RagSearchLog;
import com.sau.gym.model.entity.user.User;
import com.sau.gym.model.vo.rag.RagAnswerVO;
import com.sau.gym.model.vo.rag.RagSourceVO;
import com.sau.gym.utils.AuthContextUtil;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 作者:hfj
 * 功能:RAG 问答兼容门面
 * 说明:
 * 第二轮起 ask() 不再自己维护向量检索与范围过滤，也不直接驱动模型生成：
 * 1. 检索统一走 KnowledgeRetrievalService（纯检索，无生成模型调用）。
 * 2. 回答统一走 KnowledgeAnswerComposer（统一 LlmClient，一次生成 + 一次有界引用修正）。
 * 3. 保持原 RagAnswerVO 返回结构与独立问答页面可用。
 * 4. 不反向调用知识工具，避免 ask → tool → ask 递归。
 * 日期: 2026/4/30 16:34
 */
@Service
public class RagQaServiceImpl implements RagQaService {

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    private final KnowledgeAnswerComposer knowledgeAnswerComposer;

    private final LlmClient llmClient;

    private final RagSearchLogMapper ragSearchLogMapper;

    public RagQaServiceImpl(KnowledgeRetrievalService knowledgeRetrievalService,
                            KnowledgeAnswerComposer knowledgeAnswerComposer,
                            LlmClient llmClient,
                            RagSearchLogMapper ragSearchLogMapper) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.knowledgeAnswerComposer = knowledgeAnswerComposer;
        this.llmClient = llmClient;
        this.ragSearchLogMapper = ragSearchLogMapper;
    }

    @Override
    public RagAnswerVO ask(RagAskDto dto) {
        if (dto == null || dto.getQuestion() == null || dto.getQuestion().trim().isEmpty()) {
            throw new RuntimeException("问题不能为空");
        }

        String question = dto.getQuestion().trim();

        //1. 纯检索：范围过滤、启用/删除校验、内容版本校验都在检索服务内完成。
        KnowledgeRetrievalResult retrieval = knowledgeRetrievalService.retrieve(
                KnowledgeRetrievalRequest.builder()
                        .question(question)
                        .venueId(dto.getVenueId())
                        .courtId(dto.getCourtId())
                        .build()
        );

        RagAnswerVO vo = new RagAnswerVO();

        //2. 无证据时明确区分失败与无资料，不调用生成模型。
        if (!retrieval.hasEvidence()) {
            vo.setAnswer(buildNoEvidenceAnswer(retrieval));
            vo.setSources(new ArrayList<>());

            saveSearchLog(question, vo.getAnswer(), vo.getSources(), null, null, retrieval);
            return vo;
        }

        //3. 基于证据生成一次回答，引用不合法时最多修正一次。
        KnowledgeAnswerComposer.ComposedAnswer composed = knowledgeAnswerComposer.compose(
                question,
                retrieval.getEvidences(),
                null,
                llmClient::chat,
                null,
                "KNOWLEDGE_ANSWER",
                1
        );

        if (composed.citationsValid) {
            vo.setAnswer(composed.answer);
            vo.setSources(buildSources(composed.usedEvidences));
        } else {
            //修正后引用仍不合法：返回受控资料展示，不编造来源。
            vo.setAnswer(buildControlledDisplayAnswer(retrieval.getEvidences()));
            vo.setSources(buildSources(retrieval.getEvidences()));
        }

        //4. 记录检索日志，分数取自本次实际返回的来源。
        List<RagSourceVO> sources = vo.getSources();
        Double maxScore = sources.stream()
                .map(RagSourceVO::getScore)
                .filter(score -> score != null)
                .max(Comparator.naturalOrder())
                .orElse(null);

        Double minScore = sources.stream()
                .map(RagSourceVO::getScore)
                .filter(score -> score != null)
                .min(Comparator.naturalOrder())
                .orElse(null);

        saveSearchLog(question, vo.getAnswer(), sources, maxScore, minScore, retrieval);

        return vo;
    }

    /**
     * 构造无证据回复。
     * 检索失败、范围不适用与真实无资料分别提示。
     */
    private String buildNoEvidenceAnswer(KnowledgeRetrievalResult retrieval) {
        if (retrieval.getStatus() == RetrievalStatus.EMBEDDING_FAILED
                || retrieval.getStatus() == RetrievalStatus.SEARCH_FAILED) {
            return "知识库服务暂时不可用，请稍后再试。为避免误导，本次没有生成回答。";
        }

        if (retrieval.getStatus() == RetrievalStatus.INVALID_SCOPE) {
            return "当前场馆/场地范围内暂无适用的知识资料。"
                    + "为避免混入其他场馆规则，本次没有使用其他场馆的资料。"
                    + "请确认场馆信息后重试，或联系管理员补充当前场馆知识。";
        }

        return "知识库中暂无与该问题相关的信息，请换一种问法，或联系管理员补充场馆或场地知识。";
    }

    /**
     * 引用修正失败时的受控资料展示。
     * 只展示后端登记过的证据，不生成任何结论。
     */
    private String buildControlledDisplayAnswer(List<KnowledgeEvidence> evidences) {
        StringBuilder builder = new StringBuilder();
        builder.append("暂时无法基于可靠资料完成综合回答。以下为检索到的资料，请直接查看或换一种问法：\n");

        int index = 0;
        for (KnowledgeEvidence evidence : evidences) {
            index++;

            builder.append("\n[").append(evidence.getCitationLabel()).append("] ")
                    .append(StringUtils.hasText(evidence.getTitle()) ? evidence.getTitle() : "无标题")
                    .append("（").append(evidence.getKnowledgeScopeName());

            if (StringUtils.hasText(evidence.getVenueName())) {
                builder.append("，场馆：").append(evidence.getVenueName());
            }

            builder.append("）\n")
                    .append(preview(evidence.getText(), 160))
                    .append("\n");

            if (index >= 5) {
                break;
            }
        }

        return builder.toString();
    }

    /**
     * 由证据构造引用来源列表。
     */
    private List<RagSourceVO> buildSources(List<KnowledgeEvidence> evidences) {
        List<RagSourceVO> sources = new ArrayList<>();

        if (evidences == null) {
            return sources;
        }

        for (KnowledgeEvidence evidence : evidences) {
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

        return sources;
    }

    /**
     * 保存 RAG 检索日志。
     */
    private void saveSearchLog(String question,
                               String answer,
                               List<RagSourceVO> sources,
                               Double maxScore,
                               Double minScore,
                               KnowledgeRetrievalResult retrieval) {
        try {
            User user = AuthContextUtil.get();

            RagSearchLog log = new RagSearchLog();
            log.setTraceId(AgentTraceContext.getTraceId());
            log.setUserId(user == null ? null : user.getId());
            log.setQuestion(question);
            log.setAnswer(answer);
            log.setMatchedSources(JSON.toJSONString(sources));
            log.setMaxScore(maxScore == null ? null : BigDecimal.valueOf(maxScore));
            log.setMinScore(minScore == null ? null : BigDecimal.valueOf(minScore));
            log.setCreateTime(new Date());

            ragSearchLogMapper.insert(log);
        } catch (Exception e) {
            //日志失败不能影响用户问答。
            System.out.println("[RAG] 保存检索日志失败：" + e.getMessage()
                    + ", status=" + (retrieval == null ? null : retrieval.getStatus()));
        }
    }

    /**
     * 文本预览。
     */
    private String preview(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        String value = text.replaceAll("\\s+", " ").trim();

        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}

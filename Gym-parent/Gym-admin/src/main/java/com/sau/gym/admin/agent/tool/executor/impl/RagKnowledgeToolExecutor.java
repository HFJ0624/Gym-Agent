package com.sau.gym.admin.agent.tool.executor.impl;

import com.sau.gym.admin.agent.service.AgentToolGuardService;
import com.sau.gym.admin.agent.tool.AgentToolCodes;
import com.sau.gym.admin.agent.tool.executor.AbstractGymAgentToolExecutor;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.executor.AgentToolParamDefinition;
import com.sau.gym.admin.enums.AgentRiskLevel;
import com.sau.gym.admin.rag.retrieval.EvidenceRegistry;
import com.sau.gym.admin.rag.retrieval.KnowledgeEvidence;
import com.sau.gym.admin.rag.retrieval.KnowledgeRetrievalRequest;
import com.sau.gym.admin.rag.retrieval.KnowledgeRetrievalResult;
import com.sau.gym.admin.rag.retrieval.KnowledgeRetrievalService;
import com.sau.gym.admin.rag.retrieval.RetrievalStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:知识证据检索工具执行器
 * 说明:
 * 第二轮起本工具只做检索，返回结构化知识证据（含引用标签），
 * 不再调用任何生成式模型；回答由外层 Agent 统一生成。
 * 日期: 2026/5/27 14:59
 */
@Component
public class RagKnowledgeToolExecutor extends AbstractGymAgentToolExecutor {

    /**
     * 单条证据回传给模型的正文最大长度。
     * 完整证据保留在 EvidenceRegistry 中，供引用校验与来源展示使用。
     */
    private static final int MAX_EVIDENCE_TEXT_CHARS = 800;

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public RagKnowledgeToolExecutor(
            AgentToolGuardService agentToolGuardService,
            KnowledgeRetrievalService knowledgeRetrievalService
    ) {
        super(agentToolGuardService);
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    /**
     * 工具编码。
     * 保留原编码以兼容既有调用者与意图建议，但职责已从“知识问答”改为“知识证据检索”。
     */
    @Override
    public String toolCode() {
        return AgentToolCodes.ASK_GYM_KNOWLEDGE;
    }

    @Override
    public String toolName() {
        return "知识证据检索";
    }

    /**
     * 工具描述。
     * 明确告知模型：工具返回的是资料证据，回答必须基于证据并使用引用标签。
     */
    @Override
    public String description() {
        return "检索体育场馆平台的知识证据，返回规则、退款、取消、停车、开放时间、设施、公告和FAQ等资料的原文片段与引用标签。"
                + "回答知识类问题时应先获取证据。工具本身不生成结论，最终回答必须基于返回的证据并使用 [K1] 形式的引用标签。";
    }

    @Override
    public AgentRiskLevel riskLevel() {
        return AgentRiskLevel.LOW;
    }

    @Override
    public boolean needLogin() {
        return false;
    }

    @Override
    public boolean needConfirm() {
        return false;
    }

    @Override
    public int rateLimitSeconds() {
        return 3;
    }

    @Override
    public List<AgentToolParamDefinition> paramDefinitions() {
        return Arrays.asList(
                new AgentToolParamDefinition(
                        "question",
                        "用户原始问题",
                        "String",
                        true,
                        "取消预约后可以退款吗"
                ),
                new AgentToolParamDefinition(
                        "venueId",
                        "场馆ID",
                        "Long",
                        false,
                        "1"
                ),
                new AgentToolParamDefinition(
                        "courtId",
                        "场地ID",
                        "Long",
                        false,
                        "10"
                )
        );
    }

    /**
     * 执行纯检索。
     * Embedding、范围过滤、有效性校验的耗时与失败由检索服务单独记录。
     */
    @Override
    protected AgentToolExecuteResult doExecute(AgentToolExecuteContext context) {
        String question = context.getStringParam("question");
        Long venueId = context.getLongParam("venueId");
        Long courtId = context.getLongParam("courtId");

        KnowledgeRetrievalResult retrieval = knowledgeRetrievalService.retrieve(
                KnowledgeRetrievalRequest.builder()
                        .question(question)
                        .venueId(venueId)
                        .courtId(courtId)
                        .build()
        );

        //检索失败：明确告知模型，不允许把失败当成“没有资料”。
        if (retrieval.getStatus() == RetrievalStatus.EMBEDDING_FAILED
                || retrieval.getStatus() == RetrievalStatus.SEARCH_FAILED) {
            return AgentToolExecuteResult.failed(
                    "知识检索服务暂时不可用，无法获取资料。请直接说明暂时无法查询，不要编造规则。",
                    "RETRIEVAL_FAILED: " + retrieval.getStatus()
            );
        }

        //无证据：范围不适用与真实无资料分别说明。
        if (!retrieval.hasEvidence()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", retrieval.getStatus().name());
            data.put("scoped", retrieval.isScoped());
            data.put("detail", retrieval.getDetail());

            String message = retrieval.getStatus() == RetrievalStatus.INVALID_SCOPE
                    ? "当前场馆/场地范围内没有检索到适用资料。请直接告知用户暂无该范围的资料，或追问具体场馆，不要混用其他场馆的规则。"
                    : "知识库中没有检索到相关资料。请直接说明当前资料中没有相关说明，不要编造。";

            return AgentToolExecuteResult.success(message, data);
        }

        //命中：登记证据并返回带引用标签的证据列表。
        EvidenceRegistry registry = context.getEvidenceRegistry();

        List<Map<String, Object>> evidenceViews = new ArrayList<>();
        for (KnowledgeEvidence evidence : retrieval.getEvidences()) {
            if (registry != null) {
                registry.register(evidence);
            }

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("citationLabel", evidence.getCitationLabel());
            view.put("evidenceId", evidence.getEvidenceId());
            view.put("docId", evidence.getDocId());
            view.put("chunkIndex", evidence.getChunkIndex());
            view.put("title", evidence.getTitle());
            view.put("knowledgeScope", evidence.getKnowledgeScopeName());
            view.put("venueId", evidence.getVenueId());
            view.put("venueName", evidence.getVenueName());
            view.put("courtId", evidence.getCourtId());
            view.put("courtName", evidence.getCourtName());
            view.put("score", evidence.getScore());
            view.put("text", limit(evidence.getText()));
            evidenceViews.add(view);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", retrieval.getStatus().name());
        data.put("evidences", evidenceViews);

        String message = "已检索到 " + evidenceViews.size() + " 条资料证据（引用标签 "
                + evidenceViews.get(0).get("citationLabel") + " 等）。"
                + "回答时请基于这些资料，并在对应结论后使用 [K标签] 标注引用。";

        return AgentToolExecuteResult.success(message, data);
    }

    /**
     * 限制证据正文长度，避免超预算；完整文本保留在证据登记上下文中。
     */
    private String limit(String text) {
        if (text == null) {
            return "";
        }

        String value = text.trim();

        return value.length() <= MAX_EVIDENCE_TEXT_CHARS
                ? value
                : value.substring(0, MAX_EVIDENCE_TEXT_CHARS) + "...(已截断)";
    }
}

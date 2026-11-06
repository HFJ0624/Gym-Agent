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
import com.sau.gym.admin.rag.retrieval.KnowledgeScopeResolver;
import com.sau.gym.admin.rag.retrieval.RetrievalStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
 * 范围处理：模型未传范围时从可信业务上下文补齐；
 * courtId/venueId 归属用权威场地数据校验；跨场馆比较走受控 COMPARE 模式。
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

    private final KnowledgeScopeResolver scopeResolver;

    public RagKnowledgeToolExecutor(
            AgentToolGuardService agentToolGuardService,
            KnowledgeRetrievalService knowledgeRetrievalService,
            KnowledgeScopeResolver scopeResolver
    ) {
        super(agentToolGuardService);
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.scopeResolver = scopeResolver;
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
                ),
                new AgentToolParamDefinition(
                        "compareVenueIds",
                        "跨场馆比较时的场馆ID列表，逗号分隔，例如 1,2。仅在用户明确要求比较多个场馆时提供",
                        "String",
                        false,
                        "1,2"
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

        //1. 范围解析与归属校验：
        //模型未传范围时从可信业务上下文补齐；courtId/venueId 同时给出时用权威场地数据校验归属。
        KnowledgeScopeResolver.ScopeResolution scope;
        try {
            scope = scopeResolver.resolve(venueId, courtId, context.getBusinessContext());
        } catch (Exception e) {
            return AgentToolExecuteResult.failed(
                    "知识范围校验暂时不可用，无法确定检索范围。请直接说明暂时无法查询，不要编造规则。",
                    "SCOPE_RESOLVE_FAILED"
            );
        }

        if (scope.invalid) {
            return AgentToolExecuteResult.failed(
                    "提供的场馆/场地无效：" + describeScopeReason(scope.reason)
                            + "。请先向用户确认正确的场馆或场地，再重新检索。",
                    "INVALID_SCOPE: " + scope.reason
            );
        }

        //2. 跨场馆比较参数：仅接受合法数字 ID，且限制在少量场馆内。
        List<Long> compareVenueIds;
        try {
            compareVenueIds = scopeResolver.parseCompareVenueIds(context.getStringParam("compareVenueIds"));
        } catch (Exception e) {
            compareVenueIds = null;
        }

        if (StringUtils.hasText(context.getStringParam("compareVenueIds")) && compareVenueIds == null) {
            return AgentToolExecuteResult.paramError(
                    "compareVenueIds 参数格式不正确，应为逗号分隔的场馆ID，例如 1,2。",
                    "invalid compareVenueIds"
            );
        }

        KnowledgeRetrievalResult retrieval = knowledgeRetrievalService.retrieve(
                KnowledgeRetrievalRequest.builder()
                        .question(question)
                        .venueId(scope.venueId)
                        .courtId(scope.courtId)
                        .compareVenueIds(compareVenueIds)
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

        //无证据：范围不适用、范围缺失与真实无资料分别说明。
        if (!retrieval.hasEvidence()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", retrieval.getStatus().name());
            data.put("scoped", retrieval.isScoped());
            data.put("detail", retrieval.getDetail());

            String message;
            if (retrieval.getStatus() == RetrievalStatus.INVALID_SCOPE) {
                message = "当前场馆/场地范围内没有检索到适用资料。请直接告知用户暂无该范围的资料，或追问具体场馆，不要混用其他场馆的规则。";
            } else if (!retrieval.isScoped()) {
                message = "未提供场馆/场地范围，已只检索到平台级公共规则。如问题依赖具体场馆规则，请先向用户确认场馆后重新检索。";
            } else {
                message = "知识库中没有检索到相关资料。请直接说明当前资料中没有相关说明，不要编造。";
            }

            return AgentToolExecuteResult.success(message, data);
        }

        //命中：登记证据并返回带引用标签的证据列表。
        //标签必须使用 Registry 返回的规范值，
        //同一轮多次检索同一 chunk 时新对象也要带上已有标签，不能出现 null 标签。
        EvidenceRegistry registry = context.getEvidenceRegistry();

        List<Map<String, Object>> evidenceViews = new ArrayList<>();
        for (KnowledgeEvidence evidence : retrieval.getEvidences()) {
            String citationLabel = registry == null ? evidence.getCitationLabel() : registry.register(evidence);

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("citationLabel", citationLabel);
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

    /**
     * 范围校验失败原因的用户可读说明。
     */
    private String describeScopeReason(String reason) {
        if (reason == null) {
            return "范围校验未通过";
        }

        return switch (reason) {
            case "COURT_NOT_FOUND" -> "场地不存在";
            case "VENUE_NOT_FOUND" -> "场馆不存在";
            case "COURT_VENUE_MISMATCH" -> "场地不属于该场馆";
            case "SCOPE_CHECK_UNAVAILABLE" -> "范围校验服务暂时不可用";
            default -> "范围校验未通过（" + reason + "）";
        };
    }
}

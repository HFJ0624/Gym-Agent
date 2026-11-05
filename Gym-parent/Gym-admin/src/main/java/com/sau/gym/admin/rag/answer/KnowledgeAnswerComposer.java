package com.sau.gym.admin.rag.answer;

import com.sau.gym.admin.agent.llm.LlmChatRequest;
import com.sau.gym.admin.agent.llm.LlmChatResponse;
import com.sau.gym.admin.agent.llm.LlmClient;
import com.sau.gym.admin.agent.service.AgentTraceService;
import com.sau.gym.admin.rag.retrieval.EvidenceRegistry;
import com.sau.gym.admin.rag.retrieval.KnowledgeEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 作者:hfj
 * 功能:知识回答生成组件
 * 说明:
 * 1. 输入必须是检索服务返回的证据，本组件负责调用一次模型组织最终回答。
 * 2. 引用标签由后端登记与校验；引用不合法时最多做一次有预算的修正，
 *    仍失败则由调用方返回受控资料展示。
 * 3. 模型调用通过 ModelInvoker 注入：
 *    - 独立问答走统一 LlmClient；
 *    - Agent 运行走模型拦截器链，计入共享调用预算。
 */
@Component
public class KnowledgeAnswerComposer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAnswerComposer.class);

    /**
     * 模型调用方式。
     */
    public interface ModelInvoker {
        LlmChatResponse invoke(LlmChatRequest request);
    }

    /**
     * 组合结果。
     */
    public static class ComposedAnswer {
        public final String answer;
        public final List<KnowledgeEvidence> usedEvidences;
        public final boolean citationsValid;
        public final int modelCalls;
        public final long costMs;

        public ComposedAnswer(String answer,
                              List<KnowledgeEvidence> usedEvidences,
                              boolean citationsValid,
                              int modelCalls,
                              long costMs) {
            this.answer = answer;
            this.usedEvidences = usedEvidences;
            this.citationsValid = citationsValid;
            this.modelCalls = modelCalls;
            this.costMs = costMs;
        }
    }

    private final AgentTraceService agentTraceService;

    public KnowledgeAnswerComposer(AgentTraceService agentTraceService) {
        this.agentTraceService = agentTraceService;
    }

    /**
     * 基于证据生成回答并校验引用。
     *
     * @param question    用户问题
     * @param evidences   检索证据
     * @param registry    证据登记上下文；为空时内部创建临时上下文
     * @param invoker     模型调用方式
     * @param timeoutMs   本次调用超时，可为空
     * @param purpose     用途标识，例如 KNOWLEDGE_ANSWER / ANSWER_REPAIR
     * @param maxRepairs  引用不合法时的最大修正次数
     */
    public ComposedAnswer compose(String question,
                                  List<KnowledgeEvidence> evidences,
                                  EvidenceRegistry registry,
                                  ModelInvoker invoker,
                                  Integer timeoutMs,
                                  String purpose,
                                  int maxRepairs) {
        long start = System.currentTimeMillis();

        EvidenceRegistry effectiveRegistry = registry != null ? registry : new EvidenceRegistry();

        if (evidences != null) {
            evidences.forEach(effectiveRegistry::register);
        }

        List<KnowledgeEvidence> registered = new ArrayList<>(effectiveRegistry.all());
        int calls = 0;

        //1. 第一次生成。
        String answer = invokeModel(invoker,
                KnowledgePrompts.answerPrompt(question, registered),
                timeoutMs,
                purpose);
        calls++;

        //2. 引用校验。
        List<String> labels = CitationExtractor.extract(answer);
        boolean valid = isCitationValid(labels, effectiveRegistry, registered);

        traceValidate(labels, valid, effectiveRegistry.size());

        //3. 最多一次修正。
        if (!valid && maxRepairs > 0) {
            List<String> validLabels = new ArrayList<>();
            effectiveRegistry.all().forEach(item -> validLabels.add(item.getCitationLabel()));

            String repaired = invokeModel(invoker,
                    KnowledgePrompts.repairPrompt(answer, validLabels, registered),
                    timeoutMs,
                    "ANSWER_REPAIR");
            calls++;

            List<String> repairedLabels = CitationExtractor.extract(repaired);

            if (isCitationValid(repairedLabels, effectiveRegistry, registered)) {
                answer = repaired;
                labels = repairedLabels;
                valid = true;

                traceValidate(labels, true, effectiveRegistry.size());
            } else {
                traceValidate(repairedLabels, false, effectiveRegistry.size());
            }
        }

        //4. 标记实际使用的证据；校验失败时由调用方决定降级展示。
        List<KnowledgeEvidence> used = effectiveRegistry.markUsed(valid ? labels : List.of());

        long cost = System.currentTimeMillis() - start;
        traceCompose(purpose, question, used.size(), valid, cost);

        return new ComposedAnswer(answer, used, valid, calls, cost);
    }

    /**
     * 引用是否合法：
     * 1. 所有标签必须真实登记过。
     * 2. 提供了证据时至少引用一个标签，避免“知识结论没有依据”。
     */
    private boolean isCitationValid(List<String> labels,
                                    EvidenceRegistry registry,
                                    List<KnowledgeEvidence> registered) {
        if (labels == null || labels.isEmpty()) {
            return registered.isEmpty();
        }

        for (String label : labels) {
            if (registry.get(label) == null) {
                return false;
            }
        }

        return true;
    }

    /**
     * 调用模型。
     */
    private String invokeModel(ModelInvoker invoker, String prompt, Integer timeoutMs, String purpose) {
        LlmChatRequest request = LlmChatRequest.builder()
                .messages(List.of(com.sau.gym.admin.agent.llm.LlmMessage.user(prompt)))
                .timeoutMs(timeoutMs)
                .build();
        request.setPurpose(purpose);

        LlmChatResponse response = invoker.invoke(request);

        String content = response == null ? null : response.content();

        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("模型没有返回有效内容");
        }

        return content.trim();
    }

    /**
     * 记录引用校验结果。
     */
    private void traceValidate(List<String> labels, boolean valid, int registeredCount) {
        try {
            agentTraceService.addStep(
                    "EVIDENCE_VALIDATE",
                    valid ? "引用校验通过" : "引用校验未通过",
                    "registered=" + registeredCount,
                    "citations=" + labels,
                    valid ? "SUCCESS" : "FAILED",
                    valid ? null : "回答引用了未登记的标签或没有引用",
                    0L
            );
        } catch (Exception e) {
            log.warn("[KnowledgeAnswerComposer] Trace 写入失败: {}", e.getMessage());
        }
    }

    /**
     * 记录回答组装。
     */
    private void traceCompose(String purpose, String question, int usedCount, boolean valid, long cost) {
        try {
            agentTraceService.addStep(
                    "ANSWER_COMPOSE",
                    "知识回答组装: " + purpose,
                    StringUtils.hasText(question) ? question : "",
                    "usedEvidence=" + usedCount + ", citationsValid=" + valid,
                    "SUCCESS",
                    null,
                    cost
            );
        } catch (Exception e) {
            log.warn("[KnowledgeAnswerComposer] Trace 写入失败: {}", e.getMessage());
        }
    }
}

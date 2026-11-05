package com.sau.gym.admin.rag.answer;

import com.sau.gym.admin.rag.retrieval.KnowledgeEvidence;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 作者:hfj
 * 功能:知识回答提示词构造
 * 说明:
 * 1. 检索服务只给证据，回答生成统一使用这里的提示词。
 * 2. 模型只能引用提示词中实际提供过的 [K1]、[K2] 标签，
 *    引用合法性由后端 EvidenceRegistry 校验。
 * 3. 提示词只描述回答方法，不硬编码停车费用、手续费等业务事实。
 */
public final class KnowledgePrompts {

    private KnowledgePrompts() {
    }

    /**
     * 构造基于证据的知识回答提示词。
     *
     * @param question  用户问题
     * @param evidences 检索证据（已带引用标签）
     * @return 提示词
     */
    public static String answerPrompt(String question, List<KnowledgeEvidence> evidences) {
        StringBuilder builder = new StringBuilder();

        builder.append("你是体育场馆预约平台的知识客服。请严格根据下方资料回答用户问题。\n\n");
        builder.append("【回答要求】\n");
        builder.append("1. 只使用下方资料中的信息回答，不得编造价格、费用、时间、规则。\n");
        builder.append("2. 每条来自资料的结论后面标注引用标签，格式为 [K1]、[K2]；一次回答可以使用多个标签。\n");
        builder.append("3. 资料中没有的信息，直接说明“当前资料中没有相关说明”，不要猜测。\n");
        builder.append("4. 注意资料适用的场馆/场地范围，不要把 A 场馆的规则套到 B 场馆。\n");
        builder.append("5. 回答使用简体中文，简洁直接。\n\n");

        builder.append("【资料】\n");
        for (KnowledgeEvidence evidence : evidences) {
            builder.append("[").append(evidence.getCitationLabel()).append("] ")
                    .append(StringUtils.hasText(evidence.getTitle()) ? evidence.getTitle() : "无标题")
                    .append("（").append(evidence.getKnowledgeScopeName()).append("");

            if (StringUtils.hasText(evidence.getVenueName())) {
                builder.append("，场馆：").append(evidence.getVenueName());
            }

            if (StringUtils.hasText(evidence.getCourtName())) {
                builder.append("，场地：").append(evidence.getCourtName());
            }

            builder.append("）\n");
            builder.append(evidence.getText() == null ? "" : evidence.getText()).append("\n\n");
        }

        builder.append("【用户问题】\n").append(question).append("\n");

        return builder.toString();
    }

    /**
     * 构造引用修正提示词。
     * 最多执行一次，仍失败则返回受控资料展示。
     *
     * @param originalAnswer 模型原始回答
     * @param validLabels    本次允许引用的标签
     * @param evidences      标签对应的证据摘要
     * @return 修正提示词
     */
    public static String repairPrompt(String originalAnswer,
                                      List<String> validLabels,
                                      List<KnowledgeEvidence> evidences) {
        StringBuilder builder = new StringBuilder();

        builder.append("你上一轮回答中的知识引用不合法。请按要求修正回答。\n\n");
        builder.append("【规则】\n");
        builder.append("1. 只能使用以下引用标签：")
                .append(String.join("、", validLabels.isEmpty() ? List.of("（无可用标签）") : validLabels))
                .append("。\n");
        builder.append("2. 引用格式必须是 [K1] 这样紧贴在结论后面的方括号标签。\n");
        builder.append("3. 不要发明新的标签，例如 [K99]。\n");
        builder.append("4. 只输出修正后的完整回答，不要输出其他解释。\n\n");

        builder.append("【可用资料摘要】\n");
        for (KnowledgeEvidence evidence : evidences) {
            builder.append("[").append(evidence.getCitationLabel()).append("] ")
                    .append(StringUtils.hasText(evidence.getTitle()) ? evidence.getTitle() : "无标题")
                    .append("\n");
        }

        builder.append("\n【原始回答】\n").append(originalAnswer).append("\n");

        return builder.toString();
    }
}

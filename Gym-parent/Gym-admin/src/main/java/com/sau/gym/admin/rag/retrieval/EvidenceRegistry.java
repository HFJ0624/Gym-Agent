package com.sau.gym.admin.rag.retrieval;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:一次运行中的证据登记上下文
 * 说明:
 * 1. 知识工具每次检索到的证据都登记到这里，并分配 K1、K2 等引用标签。
 * 2. 模型只允许引用这里登记过的标签，最终回答由后端校验。
 * 3. 区分 retrievedEvidence（全部登记）与 usedEvidence（回答实际引用）。
 * 生命周期与一次 AgentRunContext 相同，不跨轮共享。
 */
public class EvidenceRegistry {

    /**
     * 引用标签 -> 证据。
     */
    private final Map<String, KnowledgeEvidence> byLabel = new LinkedHashMap<>();

    /**
     * evidenceId -> 引用标签，用于多次检索去重。
     */
    private final Map<String, String> labelByEvidenceId = new LinkedHashMap<>();

    /**
     * 回答实际引用的标签。
     */
    private final List<String> usedLabels = new ArrayList<>();

    /**
     * 标签计数器。
     */
    private int counter = 0;

    /**
     * 登记一条证据，返回分配的引用标签。
     * 同一 evidenceId 重复登记时返回已有标签，
     * 并把规范标签写回传入的新对象，避免调用方从新对象读取到 null。
     */
    public synchronized String register(KnowledgeEvidence evidence) {
        if (evidence == null || !StringUtils.hasText(evidence.getEvidenceId())) {
            return null;
        }

        String exists = labelByEvidenceId.get(evidence.getEvidenceId());
        if (exists != null) {
            evidence.setCitationLabel(exists);
            return exists;
        }

        counter++;
        String label = "K" + counter;
        evidence.setCitationLabel(label);

        byLabel.put(label, evidence);
        labelByEvidenceId.put(evidence.getEvidenceId(), label);

        return label;
    }

    /**
     * 读取指定标签的证据。
     */
    public synchronized KnowledgeEvidence get(String label) {
        return label == null ? null : byLabel.get(label.trim());
    }

    /**
     * 是否登记过证据。
     */
    public synchronized boolean isEmpty() {
        return byLabel.isEmpty();
    }

    /**
     * 全部已登记证据。
     */
    public synchronized Collection<KnowledgeEvidence> all() {
        return new ArrayList<>(byLabel.values());
    }

    /**
     * 标记回答实际引用的标签。
     * 只保留真实存在的标签，返回对应的证据列表。
     */
    public synchronized List<KnowledgeEvidence> markUsed(Collection<String> labels) {
        usedLabels.clear();

        if (labels != null) {
            for (String label : labels) {
                if (StringUtils.hasText(label) && byLabel.containsKey(label.trim())
                        && !usedLabels.contains(label.trim())) {
                    usedLabels.add(label.trim());
                }
            }
        }

        return usedEvidences();
    }

    /**
     * 回答实际使用的证据。
     */
    public synchronized List<KnowledgeEvidence> usedEvidences() {
        List<KnowledgeEvidence> result = new ArrayList<>();
        for (String label : usedLabels) {
            result.add(byLabel.get(label));
        }
        return result;
    }

    /**
     * 全部登记数量。
     */
    public synchronized int size() {
        return byLabel.size();
    }
}

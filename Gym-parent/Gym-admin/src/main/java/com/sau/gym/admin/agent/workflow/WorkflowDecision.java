package com.sau.gym.admin.agent.workflow;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:流程路由决策
 */
@Data
public class WorkflowDecision {

    /**
     * 选中的流程。
     */
    private WorkflowType type;

    /**
     * 选择原因，写入 Trace。
     */
    private String reason;

    /**
     * 流程所需的结构化参数，例如确认码、预约ID。
     */
    private Map<String, Object> payload = new LinkedHashMap<>();

    public static WorkflowDecision of(WorkflowType type, String reason) {
        WorkflowDecision decision = new WorkflowDecision();
        decision.setType(type);
        decision.setReason(reason);
        return decision;
    }

    /**
     * 追加参数。
     */
    public WorkflowDecision with(String key, Object value) {
        this.payload.put(key, value);
        return this;
    }
}

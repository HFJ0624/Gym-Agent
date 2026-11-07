package com.sau.gym.model.vo.agent;

import lombok.Data;

import java.io.Serializable;

/**
 * 作者:hfj
 * 功能:会话快照中的最新运行信息
 * 说明:
 * 只包含恢复展示所需字段；status 为 AgentRunStatus.name()。
 */
@Data
public class AgentRunInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 运行标识。
     */
    private String runId;

    /**
     * 状态：RUNNING/COMPLETED/NEED_CLARIFICATION/WAITING_CONFIRMATION/FAILED/BUDGET_EXCEEDED/INTERRUPTED。
     */
    private String status;

    /**
     * 阶段：CONSULTING/WAITING_CONFIRMATION/CONFIRMING/FINISHING。
     */
    private String stage;

    /**
     * 错误码。
     */
    private String errorCode;

    /**
     * 会话历史是否成功提交（null 表示未知）。
     */
    private Boolean sessionCommitted;

    /**
     * 业务标识（如商品确认 confirmationId），用于关联查询办理结果。
     */
    private String businessId;

    /**
     * 更新时间（epoch 毫秒）。
     */
    private Long updateTime;
}

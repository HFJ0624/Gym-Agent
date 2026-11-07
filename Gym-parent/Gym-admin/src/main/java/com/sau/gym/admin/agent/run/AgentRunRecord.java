package com.sau.gym.admin.agent.run;

import lombok.Data;

import java.io.Serializable;

/**
 * 作者:hfj
 * 功能:一次 Agent 运行的持久记录
 * 说明:
 * 1. 只保存恢复所需的最小状态，不保存模型请求、工具原始结果、
 *    密钥或确认凭证等敏感内容。
 * 2. status 使用 AgentRunStatus 名称；终态前为 RUNNING，
 *    进程中断且租约失效后由条件写识别为 INTERRUPTED。
 * 3. sessionCommitted 表示会话历史是否成功提交；revision 为对应快照版本。
 */
@Data
public class AgentRunRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 运行标识。
     */
    private String runId;

    /**
     * 用户ID。
     */
    private Long userId;

    /**
     * 会话ID。
     */
    private String sessionId;

    /**
     * 状态（AgentRunStatus.name()）。
     */
    private String status;

    /**
     * 阶段（AgentRunStage.name()），终态后保留最后阶段。
     */
    private String stage;

    /**
     * 错误码，终态为失败/预算类时记录。
     */
    private String errorCode;

    /**
     * 会话历史是否成功提交。
     */
    private Boolean sessionCommitted;

    /**
     * 对应会话快照版本（-1 表示未知）。
     */
    private Long revision;

    /**
     * 业务标识（如商品确认 confirmationId）。
     */
    private String businessId;

    /**
     * 创建时间（epoch 毫秒）。
     */
    private Long createTime;

    /**
     * 更新时间（epoch 毫秒）。
     */
    private Long updateTime;
}

package com.sau.gym.admin.agent.runtime;

import com.sau.gym.admin.agent.memory.model.SessionMessage;
import com.sau.gym.model.vo.agent.AgentSourceVO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:一轮 Agent 运行结果
 * 说明:
 * 内部使用结构化对象表达终态，在入口处再适配成当前对外接口格式。
 */
@Data
public class AgentRunResult {

    /**
     * 运行终态。
     */
    private AgentRunStatus status;

    /**
     * 给用户看的纯文本回复。
     */
    private String replyText;

    /**
     * 需要结构化展示时给前端的兼容 JSON 字符串。
     * 例如草稿卡片，必须由后端工具结果组装，不能由模型重新生成。
     */
    private String displayPayload;

    /**
     * 待确认草稿类型，便于 Trace 和后续扩展。
     */
    private String pendingDraftType;

    /**
     * 错误码，失败或预算终止时使用。
     */
    private String errorCode;

    /**
     * 本轮新增的会话消息，由 Harness 统一提交。
     */
    private List<SessionMessage> newMessages = new ArrayList<>();

    /**
     * 模型实际调用次数。
     */
    private int modelCallCount;

    /**
     * 工具实际派发次数。
     */
    private int toolCallCount;

    /**
     * 本轮耗时。
     */
    private long costMs;

    /**
     * 会话标识，由入口服务回填。
     */
    private String sessionId;

    /**
     * 业务标识（如商品确认 confirmationId），由 Workflow 按真实业务结果回填，
     * 用于运行记录与恢复查询关联订单。
     */
    private String businessId;

    /**
     * 显式业务失败标记：办理类业务返回明确失败时置为 true，
     * 使 Trace 与恢复展示不把该轮记成业务成功。
     */
    private boolean businessFailure = false;

    /**
     * 本轮运行标识，由入口服务回填。
     */
    private String runId;

    /**
     * 会话历史提交是否成功。
     */
    private boolean sessionCommitted = true;

    /**
     * 回答实际引用的证据来源（后端校验后）。
     */
    private List<AgentSourceVO> sources = new ArrayList<>();

    /**
     * 草稿等卡片数据，来自真实工具结果与会话草稿。
     */
    private List<Map<String, Object>> cards = new ArrayList<>();

    /**
     * 后端基于当前待办生成的允许操作。
     */
    private List<Map<String, Object>> actions = new ArrayList<>();

    /**
     * 构造正常完成结果。
     */
    public static AgentRunResult completed(String replyText) {
        AgentRunResult result = new AgentRunResult();
        result.setStatus(AgentRunStatus.COMPLETED);
        result.setReplyText(replyText);
        return result;
    }

    /**
     * 构造追问结果。
     */
    public static AgentRunResult clarification(String question) {
        AgentRunResult result = new AgentRunResult();
        result.setStatus(AgentRunStatus.NEED_CLARIFICATION);
        result.setReplyText(question);
        return result;
    }

    /**
     * 构造待确认结果。
     */
    public static AgentRunResult waitingConfirmation(String replyText, String displayPayload, String draftType) {
        AgentRunResult result = new AgentRunResult();
        result.setStatus(AgentRunStatus.WAITING_CONFIRMATION);
        result.setReplyText(replyText);
        result.setDisplayPayload(displayPayload);
        result.setPendingDraftType(draftType);
        return result;
    }

    /**
     * 构造失败结果。
     */
    public static AgentRunResult failed(String replyText, String errorCode) {
        AgentRunResult result = new AgentRunResult();
        result.setStatus(AgentRunStatus.FAILED);
        result.setReplyText(replyText);
        result.setErrorCode(errorCode);
        return result;
    }

    /**
     * 构造预算耗尽结果。
     */
    public static AgentRunResult budgetExceeded(String replyText, String errorCode) {
        AgentRunResult result = new AgentRunResult();
        result.setStatus(AgentRunStatus.BUDGET_EXCEEDED);
        result.setReplyText(replyText);
        result.setErrorCode(errorCode);
        return result;
    }

    /**
     * 是否属于业务上成功的终态。
     */
    public boolean isBusinessSuccess() {
        if (businessFailure) {
            return false;
        }

        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.NEED_CLARIFICATION
                || status == AgentRunStatus.WAITING_CONFIRMATION;
    }

    public void setBusinessFailure(boolean businessFailure) {
        this.businessFailure = businessFailure;
    }
}

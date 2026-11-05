package com.sau.gym.model.dto.agent;

import com.sau.gym.model.vo.agent.AgentSourceVO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:结构化客服回复
 * 说明:
 * 由后端 Java 根据本次执行结果组装：
 * 1. message 是最终客服文字，正文中的 [K标签] 与 sources 一一对应。
 * 2. sources 是后端校验过的实际引用证据，不是全部检索候选。
 * 3. cards 是真实工具返回的草稿等卡片数据。
 * 4. actions 是后端基于当前待办生成的允许操作。
 * 模型不创建用户身份、确认凭证、业务成功状态或可执行动作。
 */
@Data
public class AgentChatResponse {

    /**
     * 会话标识。
     */
    private String sessionId;

    /**
     * 本轮运行标识（与 traceId 一致）。
     */
    private String runId;

    /**
     * 运行终态：COMPLETED / NEED_CLARIFICATION / WAITING_CONFIRMATION / FAILED / BUDGET_EXCEEDED。
     */
    private String status;

    /**
     * 最终客服文字。
     */
    private String message;

    /**
     * 回答引用来源。
     */
    private List<AgentSourceVO> sources = new ArrayList<>();

    /**
     * 草稿等卡片数据。
     * type: BOOKING / SHOPPING / CANCEL_BOOKING
     * data: 草稿字段
     * confirmToken / confirmAction: 来自真实草稿
     */
    private List<Map<String, Object>> cards = new ArrayList<>();

    /**
     * 允许操作。
     * 例如: {"type":"confirm","label":"确认预约","command":"确认预约 123456"}
     *       {"type":"cancel","label":"取消","command":"取消"}
     */
    private List<Map<String, Object>> actions = new ArrayList<>();
}

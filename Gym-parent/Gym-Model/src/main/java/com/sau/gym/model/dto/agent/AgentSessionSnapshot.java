package com.sau.gym.model.dto.agent;

import com.sau.gym.model.vo.agent.AgentRunInfo;
import com.sau.gym.model.vo.agent.AgentSourceVO;
import com.sau.gym.model.vo.agent.AgentTurnVO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:面向客服页面的会话恢复快照
 * 说明:
 * 一个只读接口返回最近展示对话、最新运行信息与当前有效待办；
 * 不包含系统提示、摘要全文、tool_calls、工具原始结果或错误堆栈。
 */
@Data
public class AgentSessionSnapshot {

    /**
     * 会话标识。
     */
    private String sessionId;

    /**
     * 历史读取是否失败；true 时调用方不得把快照当作完整状态。
     */
    private boolean loadFailed;

    /**
     * 运行状态是否可查询；false 表示 Redis 暂时不可用，与“无会话”区分。
     */
    private boolean runAvailable = true;

    /**
     * 最新运行信息；无运行时为 null。
     */
    private AgentRunInfo run;

    /**
     * 最近展示轮次（有界，按时间升序）。
     */
    private List<AgentTurnVO> turns = new ArrayList<>();

    /**
     * 当前有效草稿卡片（来自实时读取的 PendingDraft）。
     */
    private List<Map<String, Object>> cards = new ArrayList<>();

    /**
     * 当前有效草稿允许的操作。
     */
    private List<Map<String, Object>> actions = new ArrayList<>();
}

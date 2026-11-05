package com.sau.gym.admin.agent.harness;

import com.sau.gym.model.dto.agent.AgentChatDto;
import lombok.Builder;
import lombok.Data;

/**
 * 作者:hfj
 * 功能:Harness 入参
 * 说明:
 * userId 必须是可信身份解析后的结果，Harness 不再自行推断身份。
 */
@Data
@Builder
public class AgentHarnessRequest {

    /**
     * 可信用户ID，未登录可为空。
     */
    private Long userId;

    /**
     * 会话标识，跨轮稳定。
     */
    private String sessionId;

    /**
     * 本轮运行标识，每轮独立。
     */
    private String runId;

    /**
     * 用户原始消息。
     */
    private String message;

    /**
     * 原始请求参数，包含页面场馆、场地等上下文。
     */
    private AgentChatDto chatDto;
}

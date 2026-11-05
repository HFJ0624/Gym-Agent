package com.sau.gym.admin.agent.harness.interceptor;

import com.sau.gym.admin.agent.runtime.AgentRunContext;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 作者:hfj
 * 功能:一次工具派发的入参
 */
@Data
@Builder
public class ToolCallRequest {

    /**
     * 本轮运行上下文。
     */
    private AgentRunContext runContext;

    /**
     * 模型给出的工具调用 ID，必须原样回填到 tool 消息。
     */
    private String toolCallId;

    /**
     * 工具编码。
     */
    private String toolCode;

    /**
     * 模型给出的原始参数 JSON。
     */
    private String argumentsJson;

    /**
     * 解析后的参数，未解析成功时为空。
     */
    private Map<String, Object> arguments;

    /**
     * 本条第几工具调用，从 1 开始。
     */
    private int callIndex;

    /**
     * 参数解析失败原因，非空表示参数结构不可用。
     */
    private String argumentError;
}

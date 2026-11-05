package com.sau.gym.admin.agent.harness.interceptor;

import com.sau.gym.admin.agent.llm.LlmChatResponse;
import com.sau.gym.admin.agent.llm.LlmToolDefinition;
import com.sau.gym.admin.agent.llm.LlmMessage;
import com.sau.gym.admin.agent.runtime.AgentRunContext;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 作者:hfj
 * 功能:一次模型调用的入参
 * 说明:
 * 由运行时组装，依次经过模型拦截器链，最后交给 LlmClient。
 */
@Data
@Builder
public class ModelCallRequest {

    /**
     * 本轮运行上下文。
     */
    private AgentRunContext runContext;

    /**
     * 已组装的消息列表。
     */
    private List<LlmMessage> messages;

    /**
     * 本轮可用工具定义。
     */
    private List<LlmToolDefinition> tools;

    /**
     * 第几次模型请求，从 1 开始。
     */
    private int attempt;

    /**
     * 本次请求可用的超时毫秒数，由预算裁剪得出。
     */
    private Integer timeoutMs;

    /**
     * 拦截器可写入的响应，便于链上后续组件读取。
     */
    private LlmChatResponse response;

    /**
     * 转换成底层协议请求。
     * 只在链尾调用，保证中间环节无法绕过模型拦截器直接发请求。
     */
    public com.sau.gym.admin.agent.llm.LlmChatRequest toLlmChatRequest() {
        com.sau.gym.admin.agent.llm.LlmChatRequest chatRequest =
                new com.sau.gym.admin.agent.llm.LlmChatRequest();

        chatRequest.setMessages(messages);
        chatRequest.setTools(tools == null || tools.isEmpty() ? null : tools);
        chatRequest.setToolChoice(tools == null || tools.isEmpty() ? null : "auto");
        chatRequest.setTimeoutMs(timeoutMs);

        return chatRequest;
    }
}

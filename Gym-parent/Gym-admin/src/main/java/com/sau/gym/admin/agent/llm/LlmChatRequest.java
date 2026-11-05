package com.sau.gym.admin.agent.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 作者:hfj
 * 功能:Chat Completions 请求体
 * 说明:
 * 只声明本项目实际使用的协议字段，不假设服务端支持 strict 或额外思考参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmChatRequest {

    /**
     * 模型名称。
     */
    private String model;

    /**
     * 消息列表。
     */
    private List<LlmMessage> messages;

    /**
     * 可用工具列表，为空时不传，避免部分服务拒绝空数组。
     */
    private List<LlmToolDefinition> tools;

    /**
     * 工具选择策略。
     */
    @JsonProperty("tool_choice")
    private String toolChoice;

    /**
     * 采样温度。
     */
    private Double temperature;

    /**
     * 最大输出 Token 数。
     */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /**
     * 本次请求的毫秒超时。
     * 不参与序列化，由客户端读取，取“配置超时”和“本轮剩余预算”的较小值。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Integer timeoutMs;
}

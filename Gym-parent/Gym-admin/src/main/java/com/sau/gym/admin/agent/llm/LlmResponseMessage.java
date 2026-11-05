package com.sau.gym.admin.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 作者:hfj
 * 功能:模型响应中的 assistant 消息
 * 说明:
 * 额外保留 reasoning_content 字段，读取后不参与后续请求回传，
 * 避免把推理过程当成普通内容重新发给不支持该字段的服务。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmResponseMessage {

    private String role;

    private String content;

    @JsonProperty("tool_calls")
    private List<LlmToolCall> toolCalls;

    @JsonProperty("reasoning_content")
    private String reasoningContent;
}

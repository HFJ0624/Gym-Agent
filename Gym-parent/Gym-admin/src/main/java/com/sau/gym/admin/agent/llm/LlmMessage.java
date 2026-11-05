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
 * 功能:Chat Completions 协议中的一条消息
 * 说明:
 * 一条消息可能是 system / user / assistant / tool。
 * assistant 可以同时带 content 和 tool_calls；tool 消息必须带 tool_call_id。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmMessage {

    /**
     * 角色。
     */
    private String role;

    /**
     * 文本内容。
     * assistant 只返回工具调用时可能为 null，这里强制序列化该字段，
     * 避免部分兼容服务因为缺少 content 字段而拒绝请求。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String content;

    /**
     * assistant 声明的工具调用。
     */
    @JsonProperty("tool_calls")
    private List<LlmToolCall> toolCalls;

    /**
     * tool 消息对应的工具调用 ID。
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /**
     * tool 消息对应的工具名称，部分兼容服务会读取该字段。
     */
    private String name;

    public static LlmMessage system(String content) {
        return LlmMessage.builder().role("system").content(content).build();
    }

    public static LlmMessage user(String content) {
        return LlmMessage.builder().role("user").content(content).build();
    }

    public static LlmMessage assistant(String content) {
        return LlmMessage.builder().role("assistant").content(content).build();
    }

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return LlmMessage.builder()
                .role("assistant")
                .content(content)
                .toolCalls(toolCalls == null || toolCalls.isEmpty() ? null : toolCalls)
                .build();
    }

    public static LlmMessage tool(String toolCallId, String toolName, String content) {
        return LlmMessage.builder()
                .role("tool")
                .toolCallId(toolCallId)
                .name(toolName)
                .content(content)
                .build();
    }
}

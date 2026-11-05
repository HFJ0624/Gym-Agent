package com.sau.gym.admin.agent.memory.model;

import com.sau.gym.admin.agent.llm.LlmMessage;
import com.sau.gym.admin.agent.llm.LlmToolCall;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 作者:hfj
 * 功能:会话中的一条协议消息
 * 说明:
 * 这是自建运行时唯一的权威会话消息结构，
 * 保留 role=system/user/assistant/tool 以及 tool_calls 与 tool_call_id 的配对关系。
 * 不保存整段 agentInput，也不保存 Trace 细节。
 */
@Data
@NoArgsConstructor
public class SessionMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 角色: system / user / assistant / tool。
     */
    private String role;

    /**
     * 文本内容。
     */
    private String content;

    /**
     * assistant 消息声明的工具调用。
     */
    private List<LlmToolCall> toolCalls;

    /**
     * tool 消息对应的工具调用 ID。
     */
    private String toolCallId;

    /**
     * tool 消息对应的工具名称。
     */
    private String toolName;

    /**
     * 写入时间，便于排查和裁剪。
     */
    private LocalDateTime createTime;

    /**
     * 构造系统消息。
     */
    public static SessionMessage system(String content) {
        SessionMessage message = new SessionMessage();
        message.setRole("system");
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now());
        return message;
    }

    /**
     * 构造用户消息。
     */
    public static SessionMessage user(String content) {
        SessionMessage message = new SessionMessage();
        message.setRole("user");
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now());
        return message;
    }

    /**
     * 构造 assistant 文本消息。
     */
    public static SessionMessage assistant(String content) {
        SessionMessage message = new SessionMessage();
        message.setRole("assistant");
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now());
        return message;
    }

    /**
     * 构造带工具调用的 assistant 消息。
     */
    public static SessionMessage assistant(String content, List<LlmToolCall> toolCalls) {
        SessionMessage message = assistant(content);
        message.setToolCalls(toolCalls);
        return message;
    }

    /**
     * 构造 tool 结果消息。
     */
    public static SessionMessage tool(String toolCallId, String toolName, String content) {
        SessionMessage message = new SessionMessage();
        message.setRole("tool");
        message.setToolCallId(toolCallId);
        message.setToolName(toolName);
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now());
        return message;
    }

    /**
     * 转换为模型请求消息。
     * tool 消息只保留 tool_call_id 与 content，不额外携带 name，
     * 避免部分严格校验的兼容服务因为多余字段拒绝请求。
     */
    public LlmMessage toLlmMessage() {
        return LlmMessage.builder()
                .role(role)
                .content(content)
                .toolCalls(toolCalls)
                .toolCallId(toolCallId)
                .name("tool".equals(role) ? null : toolName)
                .build();
    }
}

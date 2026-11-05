package com.sau.gym.admin.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 作者:hfj
 * 功能:Chat Completions 响应体
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmChatResponse {

    private String id;

    private String model;

    private List<LlmChoice> choices;

    private LlmUsage usage;

    /**
     * 读取第一个候选。
     */
    public LlmChoice firstChoice() {
        return choices == null || choices.isEmpty() ? null : choices.get(0);
    }

    /**
     * 读取回复文本，可能为空。
     */
    public String content() {
        LlmChoice choice = firstChoice();
        return choice == null || choice.getMessage() == null ? null : choice.getMessage().getContent();
    }

    /**
     * 读取工具调用列表，可能为空。
     */
    public List<LlmToolCall> toolCalls() {
        LlmChoice choice = firstChoice();
        return choice == null || choice.getMessage() == null ? null : choice.getMessage().getToolCalls();
    }

    /**
     * 读取结束原因。
     */
    public String finishReason() {
        LlmChoice choice = firstChoice();
        return choice == null ? null : choice.getFinishReason();
    }

    /**
     * 是否包含工具调用。
     */
    public boolean hasToolCalls() {
        List<LlmToolCall> calls = toolCalls();
        return calls != null && !calls.isEmpty();
    }

    /**
     * 读取实际使用的模型名，缺失时回落到响应 id。
     */
    public String effectiveModel() {
        return model != null ? model : id;
    }
}

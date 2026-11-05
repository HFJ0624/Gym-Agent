package com.sau.gym.admin.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 作者:hfj
 * 功能:模型响应中的一个候选结果
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmChoice {

    private Integer index;

    private LlmResponseMessage message;

    /**
     * 结束原因。
     * 常见值: stop / length / tool_calls / content_filter。
     */
    @JsonProperty("finish_reason")
    private String finishReason;
}

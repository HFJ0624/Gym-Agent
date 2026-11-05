package com.sau.gym.admin.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 作者:hfj
 * 功能:模型响应中的 usage 统计
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmUsage {

    @JsonProperty("prompt_tokens")
    private Integer promptTokens;

    @JsonProperty("completion_tokens")
    private Integer completionTokens;

    @JsonProperty("total_tokens")
    private Integer totalTokens;
}

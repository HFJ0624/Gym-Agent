package com.sau.gym.admin.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 作者:hfj
 * 功能:工具调用的函数部分
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmFunctionCall {

    /**
     * 函数（工具）名称。
     */
    private String name;

    /**
     * 模型生成的参数 JSON 字符串。
     * 保持字符串形态，避免把非法 JSON 强行反序列化后丢失原始信息。
     */
    private String arguments;
}

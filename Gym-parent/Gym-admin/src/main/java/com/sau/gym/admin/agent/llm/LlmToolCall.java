package com.sau.gym.admin.agent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 作者:hfj
 * 功能:模型返回的工具调用
 * 说明:
 * id 用于把 role=tool 的结果回传给模型，必须原样保留。
 * arguments 是模型生成的 JSON 字符串，由工具执行链负责解析和校验。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmToolCall {

    /**
     * 工具调用 ID。
     */
    private String id;

    /**
     * 调用类型，当前固定为 function。
     */
    private String type;

    /**
     * 函数调用内容。
     */
    private LlmFunctionCall function;

    /**
     * 读取工具名，缺少 function 时返回 null。
     */
    public String functionName() {
        return function == null ? null : function.getName();
    }

    /**
     * 读取原始参数 JSON，缺少 function 时返回 null。
     */
    public String argumentsJson() {
        return function == null ? null : function.getArguments();
    }
}

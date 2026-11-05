package com.sau.gym.admin.agent.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 作者:hfj
 * 功能:暴露给模型的工具定义（Function Calling Schema）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmToolDefinition {

    /**
     * 工具类型，当前固定为 function。
     */
    @Builder.Default
    private String type = "function";

    /**
     * 函数定义。
     */
    private Function function;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Function {

        /**
         * 函数名称，使用 Registry 的 toolCode。
         */
        private String name;

        /**
         * 函数说明。
         */
        private String description;

        /**
         * 参数 JSON Schema。
         */
        private Map<String, Object> parameters;

        /**
         * 严格模式。
         * 不默认开启，避免要求所有兼容服务都支持 strict。
         */
        @JsonProperty("strict")
        private Boolean strict;
    }
}

package com.sau.gym.admin.agent.tool;

import com.sau.gym.admin.agent.llm.LlmToolDefinition;
import com.sau.gym.admin.agent.tool.executor.AgentToolParamDefinition;
import com.sau.gym.admin.agent.tool.registry.AgentToolInfo;
import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作者:hfj
 * 功能:根据实际注册的工具生成 Function Calling Schema
 * 说明:
 * 1. toolCode 作为标准 Function 名称。
 * 2. description 作为工具说明。
 * 3. paramDefinitions 转换成 parameters JSON Schema。
 * 4. 内部参数（userId/sessionId/traceId 等）永远不暴露给模型。
 */
@Component
public class AgentToolSchemaBuilder {

    /**
     * 内部参数名，模型不可填写。
     */
    private static final Set<String> INTERNAL_PARAMS = new HashSet<>(Arrays.asList(
            "userid",
            "sessionid",
            "traceid",
            "runid"
    ));

    private final GymAgentToolRegistry registry;

    public AgentToolSchemaBuilder(GymAgentToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 根据授权工具集合生成模型工具定义。
     * 只包含真实注册过的工具，避免把逻辑意图当成可执行能力。
     */
    public List<LlmToolDefinition> build(Collection<String> toolCodes) {
        List<LlmToolDefinition> definitions = new ArrayList<>();

        if (toolCodes == null || toolCodes.isEmpty()) {
            return definitions;
        }

        for (String toolCode : toolCodes) {
            AgentToolInfo info = registry.getToolInfo(toolCode);

            if (info == null || !info.isEnabled()) {
                continue;
            }

            Map<String, Object> parameters = buildParameters(info.getParamDefinitions());

            definitions.add(LlmToolDefinition.builder()
                    .type("function")
                    .function(LlmToolDefinition.Function.builder()
                            .name(info.getToolCode())
                            .description(buildDescription(info))
                            .parameters(parameters)
                            .build())
                    .build());
        }

        return definitions;
    }

    /**
     * 构造参数 Schema。
     */
    public Map<String, Object> buildParameters(List<AgentToolParamDefinition> definitions) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        if (definitions != null) {
            for (AgentToolParamDefinition definition : definitions) {
                if (definition == null || !StringUtils.hasText(definition.getName())) {
                    continue;
                }

                if (isInternalParam(definition.getName())) {
                    continue;
                }

                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", mapType(definition.getType()));
                property.put("description", StringUtils.hasText(definition.getDescription())
                        ? definition.getDescription()
                        : definition.getName());

                if (StringUtils.hasText(definition.getExample())) {
                    property.put("example", definition.getExample());
                }

                properties.put(definition.getName(), property);

                if (definition.isRequired()) {
                    required.add(definition.getName());
                }
            }
        }

        schema.put("properties", properties);
        schema.put("required", required);

        //当前工具参数都是平面结构，明确禁止额外字段，减少模型乱传参数。
        schema.put("additionalProperties", false);

        return schema;
    }

    /**
     * 工具说明。
     * 平台级限制直接写进描述，让模型知道草稿类工具不会真正改业务状态。
     */
    private String buildDescription(AgentToolInfo info) {
        StringBuilder builder = new StringBuilder();

        if (StringUtils.hasText(info.getDescription())) {
            builder.append(info.getDescription());
        } else if (StringUtils.hasText(info.getToolName())) {
            builder.append(info.getToolName());
        }

        if (info.isNeedConfirm()) {
            builder.append(" 该工具执行后需要用户确认。");
        }

        return builder.toString();
    }

    /**
     * 参数类型映射。
     * 当前只处理工具真正使用的类型，不实现通用 JSON Schema 引擎。
     */
    private String mapType(String type) {
        if (!StringUtils.hasText(type)) {
            return "string";
        }

        String value = type.trim().toLowerCase();

        return switch (value) {
            case "long", "integer", "int", "short" -> "integer";
            case "double", "float", "bigdecimal", "number" -> "number";
            case "boolean", "bool" -> "boolean";
            default -> "string";
        };
    }

    /**
     * 是否内部参数。
     */
    private boolean isInternalParam(String name) {
        return INTERNAL_PARAMS.contains(name.trim().toLowerCase());
    }
}

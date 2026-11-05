package com.sau.gym.admin.agent.harness.interceptor.impl;

import com.sau.gym.admin.agent.harness.interceptor.ToolCallRequest;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationChain;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationInterceptor;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.executor.AgentToolParamDefinition;
import com.sau.gym.admin.agent.tool.executor.GymAgentToolExecutor;
import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 作者:hfj
 * 功能:工具参数结构与类型校验拦截器
 * 职责:
 * 1. 参数 JSON 不完整、类型错误、缺少必填时不进入业务执行。
 * 2. 拒绝模型填写 userId / sessionId 等内部参数，身份只能来自可信上下文。
 * 3. 把参数归一化成执行器期望的 Java 类型。
 * 说明:
 * 金额、日期、时间、库存、资源归属等业务校验仍由业务层负责。
 */
@Component
public class ToolArgumentInterceptor implements ToolInvocationInterceptor {

    /**
     * 内部参数，模型不可填写。
     */
    private static final Set<String> INTERNAL_PARAMS = new HashSet<>(Arrays.asList(
            "userid",
            "sessionid",
            "traceid",
            "runid"
    ));

    private final GymAgentToolRegistry registry;

    public ToolArgumentInterceptor(GymAgentToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AgentToolExecuteResult intercept(ToolCallRequest request, ToolInvocationChain chain) {
        String toolCode = request.getToolCode();

        //参数 JSON 解析失败时不能进入业务执行。
        if (StringUtils.hasText(request.getArgumentError())) {
            return AgentToolExecuteResult.paramError(
                    "工具参数格式不正确，请重新组织参数后重试。",
                    request.getArgumentError()
            );
        }

        GymAgentToolExecutor executor = registry.getExecutor(toolCode);

        if (executor == null) {
            //正常情况下授权拦截器已经拦住，这里只做防御。
            return AgentToolExecuteResult.failed("工具不存在：" + toolCode, "executor not found");
        }

        List<AgentToolParamDefinition> definitions = executor.paramDefinitions();

        Map<String, Object> arguments = request.getArguments() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(request.getArguments());

        Map<String, AgentToolParamDefinition> definitionMap = new LinkedHashMap<>();

        if (definitions != null) {
            for (AgentToolParamDefinition definition : definitions) {
                if (definition != null && StringUtils.hasText(definition.getName())) {
                    definitionMap.put(definition.getName(), definition);
                }
            }
        }

        //1. 非法字段检查。模型填写内部参数属于越权尝试，必须拒绝。
        for (String key : arguments.keySet()) {
            if (INTERNAL_PARAMS.contains(key.toLowerCase())) {
                return AgentToolExecuteResult.paramError(
                        "工具参数中不允许出现内部字段：" + key,
                        "internal param rejected: " + key
                );
            }

            if (!definitionMap.containsKey(key)) {
                return AgentToolExecuteResult.paramError(
                        "工具不支持参数：" + key,
                        "unknown param: " + key
                );
            }
        }

        //2. 必填与类型检查，并归一化参数值。
        Map<String, Object> normalized = new LinkedHashMap<>();

        for (AgentToolParamDefinition definition : definitionMap.values()) {
            Object value = arguments.get(definition.getName());

            boolean missing = value == null
                    || (value instanceof String text && !StringUtils.hasText(text));

            if (missing) {
                if (definition.isRequired()) {
                    return AgentToolExecuteResult.paramError(
                            "缺少必要参数：" + safeDescription(definition),
                            "missing required param: " + definition.getName()
                    );
                }
                continue;
            }

            Object converted = convert(definition, value);

            if (converted == INVALID_VALUE) {
                return AgentToolExecuteResult.paramError(
                        "参数【" + safeDescription(definition) + "】类型不正确，期望 "
                                + definition.getType() + "。",
                        "invalid param type: " + definition.getName() + "=" + value
                );
            }

            normalized.put(definition.getName(), converted);
        }

        request.setArguments(normalized);

        return chain.proceed(request);
    }

    /**
     * 类型转换标记对象。
     */
    private static final Object INVALID_VALUE = new Object();

    /**
     * 按参数定义转换类型。
     */
    private Object convert(AgentToolParamDefinition definition, Object value) {
        String type = definition.getType() == null ? "String" : definition.getType().trim().toLowerCase();

        try {
            return switch (type) {
                case "long" -> toLong(value);
                case "integer", "int", "short" -> toInteger(value);
                case "double", "float", "bigdecimal", "number" -> toDouble(value);
                case "boolean", "bool" -> toBoolean(value);
                default -> toStringValue(value);
            };
        } catch (Exception e) {
            return INVALID_VALUE;
        }
    }

    /**
     * 字符串参数转换。
     * 模型传对象或数组时说明参数结构不对，不能把它 String.valueOf 后当成合法业务参数。
     */
    private Object toStringValue(Object value) {
        if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
            return INVALID_VALUE;
        }

        return String.valueOf(value).trim();
    }

    private Object toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }

        String text = String.valueOf(value).trim();

        if (!text.matches("[-+]?\\d+")) {
            return INVALID_VALUE;
        }

        return Long.valueOf(text);
    }

    private Object toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }

        String text = String.valueOf(value).trim();

        if (!text.matches("[-+]?\\d+")) {
            return INVALID_VALUE;
        }

        return Integer.valueOf(text);
    }

    private Object toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        return Double.valueOf(String.valueOf(value).trim());
    }

    private Object toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }

        String text = String.valueOf(value).trim();

        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.valueOf(text);
        }

        return INVALID_VALUE;
    }

    /**
     * 参数说明兜底。
     */
    private String safeDescription(AgentToolParamDefinition definition) {
        if (StringUtils.hasText(definition.getDescription())) {
            return definition.getDescription() + "(" + definition.getName() + ")";
        }

        return definition.getName();
    }

    @Override
    public int order() {
        return 30;
    }
}

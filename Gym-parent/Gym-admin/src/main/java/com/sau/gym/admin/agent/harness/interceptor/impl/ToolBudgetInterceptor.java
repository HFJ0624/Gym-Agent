package com.sau.gym.admin.agent.harness.interceptor.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.harness.interceptor.ToolCallRequest;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationChain;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationInterceptor;
import com.sau.gym.admin.agent.runtime.AgentRunContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.enums.AgentToolExecuteStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 作者:hfj
 * 功能:工具派发预算与重复检测拦截器
 * 职责:
 * 1. 派发前检查剩余时间与会话锁。
 * 2. 按“派发尝试”计数，失败调用同样占用预算。
 * 3. 相同工具 + 相同规范化参数达到阈值后停止重复执行。
 */
@Component
public class ToolBudgetInterceptor implements ToolInvocationInterceptor {

    private final ObjectMapper objectMapper;

    public ToolBudgetInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentToolExecuteResult intercept(ToolCallRequest request, ToolInvocationChain chain) {
        AgentRunContext runContext = request.getRunContext();

        if (runContext == null) {
            return failure("运行上下文缺失，已拒绝工具调用。", "RUN_CONTEXT_MISSING");
        }

        if (!runContext.isLockAlive()) {
            return failure("会话已被其他请求接管，本轮已停止工具调用。", "LOCK_LOST");
        }

        if (runContext.isExpired()) {
            return failure("本轮耗时预算已耗尽，未执行该工具。", "TIMEOUT");
        }

        if (!runContext.hasToolCallBudget()) {
            return failure("工具调用次数已达上限，未执行该工具。", "BUDGET_EXCEEDED");
        }

        //先计数再执行，避免模型通过大量失败调用绕过预算。
        runContext.consumeToolCall();

        String signature = buildSignature(request);
        int count = runContext.recordToolSignature(signature);
        int threshold = runContext.getRepeatToolThreshold();

        if (threshold > 0 && count > threshold) {
            //重复调用不再执行，返回明确原因，让模型停止无进展重试。
            AgentToolExecuteResult result = failure(
                    "检测到相同工具与相同参数的重复调用，已停止继续执行。请基于已有结果回答，或向用户确认新的条件。",
                    "REPEAT_LIMIT"
            );
            result.setStatus(AgentToolExecuteStatus.FAILED);
            return result;
        }

        return chain.proceed(request);
    }

    /**
     * 构造工具调用签名。
     * 参数对象字段顺序不同不应被当成不同调用，因此先做 key 排序。
     */
    private String buildSignature(ToolCallRequest request) {
        String toolCode = request.getToolCode() == null ? "unknown" : request.getToolCode();

        try {
            Object canonical = canonicalize(request.getArguments());
            return toolCode + ":" + objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            //参数无法规范化时退回原始字符串，仍然可以识别完全相同的重复。
            return toolCode + ":" + String.valueOf(request.getArgumentsJson());
        }
    }

    /**
     * 递归把 Map 转成按 key 排序的 TreeMap。
     */
    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }

        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(canonicalize(item)));
            return copy;
        }

        return value;
    }

    /**
     * 构造带错误码的失败结果。
     */
    private AgentToolExecuteResult failure(String message, String errorCode) {
        AgentToolExecuteResult result = AgentToolExecuteResult.failed(message, errorCode);
        result.setErrorCode(errorCode);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("reason", errorCode);
        result.setExtra(extra);

        return result;
    }

    @Override
    public int order() {
        return 10;
    }
}

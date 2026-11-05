package com.sau.gym.admin.agent.harness.interceptor.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.harness.interceptor.ToolCallRequest;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationChain;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationInterceptor;
import com.sau.gym.admin.agent.service.AgentTraceService;
import com.sau.gym.admin.agent.tool.AgentToolCodes;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import org.springframework.stereotype.Component;

/**
 * 作者:hfj
 * 功能:工具执行观测拦截器
 * 职责:
 * 记录工具名、关键参数、执行状态与耗时。
 * 状态以 AgentToolExecuteResult 为准，不因为“没有抛异常”就记为成功。
 */
@Component
public class ToolTraceInterceptor implements ToolInvocationInterceptor {

    /**
     * 参数写入 Trace 时的最大长度。
     */
    private static final int MAX_ARGS_CHARS = 500;

    private final AgentTraceService agentTraceService;

    private final ObjectMapper objectMapper;

    public ToolTraceInterceptor(AgentTraceService agentTraceService, ObjectMapper objectMapper) {
        this.agentTraceService = agentTraceService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentToolExecuteResult intercept(ToolCallRequest request, ToolInvocationChain chain) {
        long start = System.currentTimeMillis();

        AgentToolExecuteResult result = chain.proceed(request);

        String status = result != null && result.isSuccess()
                ? String.valueOf(result.getStatus())
                : "FAILED";

        agentTraceService.addStep(
                "TOOL_CALL",
                "工具 " + request.getToolCode() + " 执行",
                buildInput(request),
                result == null ? null : result.getMessage(),
                status,
                result == null ? "empty result" : result.getErrorDetail(),
                System.currentTimeMillis() - start
        );

        //知识库工具内部仍会调用一次模型做答案生成，单独标记，避免和 Agent 循环轮数混淆。
        if (AgentToolCodes.ASK_GYM_KNOWLEDGE.equals(request.getToolCode())) {
            agentTraceService.addStep(
                    "RAG_LEGACY_MODEL",
                    "RAG 内部模型生成（legacy，不计入 Agent 模型轮数）",
                    null,
                    result == null ? null : String.valueOf(result.getMessage()),
                    status,
                    null,
                    0L
            );
        }

        return result;
    }

    /**
     * 组装 Trace 输入，限制长度避免污染日志表。
     */
    private String buildInput(ToolCallRequest request) {
        String args;

        try {
            args = objectMapper.writeValueAsString(request.getArguments());
        } catch (Exception e) {
            args = String.valueOf(request.getArgumentsJson());
        }

        if (args.length() > MAX_ARGS_CHARS) {
            args = args.substring(0, MAX_ARGS_CHARS) + "...";
        }

        return "callId=" + request.getToolCallId() + ", args=" + args;
    }

    @Override
    public int order() {
        return 40;
    }
}

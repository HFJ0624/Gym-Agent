package com.sau.gym.admin.agent.harness.interceptor.impl;

import com.sau.gym.admin.agent.harness.interceptor.ModelCallPurpose;
import com.sau.gym.admin.agent.harness.interceptor.ModelCallRequest;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationChain;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationInterceptor;
import com.sau.gym.admin.agent.llm.LlmChatResponse;
import com.sau.gym.admin.agent.llm.LlmErrorType;
import com.sau.gym.admin.agent.llm.LlmException;
import com.sau.gym.admin.agent.llm.LlmUsage;
import com.sau.gym.admin.agent.service.AgentTraceService;
import org.springframework.stereotype.Component;

/**
 * 作者:hfj
 * 功能:模型调用观测拦截器
 * 职责:
 * 记录第几次模型请求、耗时、finish_reason、usage 和错误分类。
 * 注意:这里的次数是自建 Agent 循环内的模型请求次数，
 * 不包含 RAG 工具内部另行发起的模型调用。
 */
@Component
public class ModelTraceInterceptor implements ModelInvocationInterceptor {

    private final AgentTraceService agentTraceService;

    public ModelTraceInterceptor(AgentTraceService agentTraceService) {
        this.agentTraceService = agentTraceService;
    }

    @Override
    public LlmChatResponse intercept(ModelCallRequest request, ModelInvocationChain chain) {
        long start = System.currentTimeMillis();

        //辅助用途（摘要/修正）与主循环调用分开命名，便于区分记账。
        String stepName = request.getPurpose() == null || request.getPurpose() == ModelCallPurpose.AGENT
                ? "第 " + request.getAttempt() + " 次模型请求"
                : request.getPurpose() + " 辅助模型调用";

        try {
            LlmChatResponse response = chain.proceed(request);

            agentTraceService.addStep(
                    "MODEL_CALL",
                    stepName,
                    "messages=" + sizeOf(request) + ", tools=" + (request.getTools() == null ? 0 : request.getTools().size()),
                    buildSuccessOutput(response),
                    "SUCCESS",
                    null,
                    System.currentTimeMillis() - start
            );

            return response;

        } catch (LlmException e) {
            agentTraceService.addStep(
                    "MODEL_CALL",
                    stepName,
                    "messages=" + sizeOf(request),
                    null,
                    "FAILED",
                    e.getErrorType() + ": " + e.getMessage(),
                    System.currentTimeMillis() - start
            );

            throw e;
        }
    }

    /**
     * 组装成功输出，便于后台排查。
     */
    private String buildSuccessOutput(LlmChatResponse response) {
        if (response == null) {
            return null;
        }

        LlmUsage usage = response.getUsage();

        StringBuilder builder = new StringBuilder();
        builder.append("finish_reason=").append(response.finishReason());
        builder.append(", hasToolCalls=").append(response.hasToolCalls());

        if (usage != null) {
            builder.append(", promptTokens=").append(usage.getPromptTokens());
            builder.append(", completionTokens=").append(usage.getCompletionTokens());
            builder.append(", totalTokens=").append(usage.getTotalTokens());
        }

        return builder.toString();
    }

    /**
     * 消息数量。
     */
    private int sizeOf(ModelCallRequest request) {
        return request.getMessages() == null ? 0 : request.getMessages().size();
    }

    /**
     * 错误类型判断辅助，保留给后续按类型分支处理。
     */
    protected boolean isRateLimited(LlmException e) {
        return e.getErrorType() == LlmErrorType.RATE_LIMITED;
    }

    @Override
    public int order() {
        return 20;
    }
}

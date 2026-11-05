package com.sau.gym.admin.agent.harness.interceptor.impl;

import com.sau.gym.admin.agent.harness.interceptor.ModelCallPurpose;
import com.sau.gym.admin.agent.harness.interceptor.ModelCallRequest;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationChain;
import com.sau.gym.admin.agent.harness.interceptor.ModelInvocationInterceptor;
import com.sau.gym.admin.agent.llm.LlmChatResponse;
import com.sau.gym.admin.agent.runtime.AgentBudgetExceededException;
import com.sau.gym.admin.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Component;

/**
 * 作者:hfj
 * 功能:模型调用预算拦截器
 * 职责:
 * 1. 调用前检查剩余轮数与剩余时间。
 * 2. 每次实际发起请求前消耗一次预算（重试同样计数）：
 *    AGENT 用途计入主循环轮数，CONTEXT_SUMMARY / ANSWER_REPAIR 等辅助用途计入共享辅助预算。
 * 3. 把本轮剩余时间换算成本次请求超时，避免等待超过截止时间。
 */
@Component
public class ModelBudgetInterceptor implements ModelInvocationInterceptor {

    @Override
    public LlmChatResponse intercept(ModelCallRequest request, ModelInvocationChain chain) {
        AgentRunContext runContext = request.getRunContext();

        if (runContext == null) {
            throw new AgentBudgetExceededException("RUN_CONTEXT_MISSING", "运行上下文缺失");
        }

        if (!runContext.isLockAlive()) {
            throw new AgentBudgetExceededException("LOCK_LOST", "会话锁已失效，已停止后续模型调用");
        }

        if (runContext.isExpired()) {
            throw new AgentBudgetExceededException("TIMEOUT", "本轮耗时预算已耗尽");
        }

        boolean auxCall = request.getPurpose() != null
                && request.getPurpose() != ModelCallPurpose.AGENT;

        if (auxCall) {
            //辅助调用（摘要、修正等）消耗共享的辅助预算与总预算，不占用主循环轮数。
            if (!runContext.hasAuxModelCallBudget()) {
                throw new AgentBudgetExceededException("AUX_CALL_LIMIT", "辅助模型调用次数已达上限");
            }

            if (!runContext.hasTotalModelCallBudget()) {
                throw new AgentBudgetExceededException("TOTAL_CALL_LIMIT", "本轮模型调用总次数已达上限");
            }

            runContext.consumeAuxModelCall();
        } else {
            if (!runContext.hasModelCallBudget()) {
                throw new AgentBudgetExceededException("MODEL_CALL_LIMIT", "模型调用轮数已达上限");
            }

            if (!runContext.hasTotalModelCallBudget()) {
                throw new AgentBudgetExceededException("TOTAL_CALL_LIMIT", "本轮模型调用总次数已达上限");
            }

            //在真正发请求前消耗预算，保证边界上不会多调用一次。
            runContext.consumeModelCall();
        }

        //模型请求等待时间不得超过本轮剩余预算。
        request.setTimeoutMs((int) Math.min(Integer.MAX_VALUE, runContext.remainingTimeMs()));

        return chain.proceed(request);
    }

    @Override
    public int order() {
        return 10;
    }
}

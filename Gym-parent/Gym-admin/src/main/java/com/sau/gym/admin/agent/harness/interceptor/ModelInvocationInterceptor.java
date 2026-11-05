package com.sau.gym.admin.agent.harness.interceptor;

import com.sau.gym.admin.agent.llm.LlmChatResponse;

/**
 * 作者:hfj
 * 功能:模型调用拦截器
 * 说明:
 * 采用有序责任链，每个实现只负责一个明确职责。
 * 执行循环不允许绕过链直接调用 LlmClient。
 */
public interface ModelInvocationInterceptor {

    /**
     * 拦截一次模型调用。
     *
     * @param request 模型调用入参
     * @param chain   责任链，必须显式调用 chain.proceed(request) 才会继续
     * @return 模型响应
     */
    LlmChatResponse intercept(ModelCallRequest request, ModelInvocationChain chain);

    /**
     * 执行顺序，数值越小越先执行。
     */
    int order();
}

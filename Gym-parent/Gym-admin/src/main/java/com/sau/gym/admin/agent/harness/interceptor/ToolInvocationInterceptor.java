package com.sau.gym.admin.agent.harness.interceptor;

import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;

/**
 * 作者:hfj
 * 功能:工具执行拦截器
 * 说明:
 * 授权、参数校验、预算、重复检测、结果记录都在链上完成，
 * 链尾才真正调用 Registry。
 */
public interface ToolInvocationInterceptor {

    /**
     * 拦截一次工具派发。
     *
     * @param request 工具派发入参
     * @param chain   责任链，必须显式调用 chain.proceed(request) 才会继续
     * @return 统一工具结果
     */
    AgentToolExecuteResult intercept(ToolCallRequest request, ToolInvocationChain chain);

    /**
     * 执行顺序，数值越小越先执行。
     */
    int order();
}

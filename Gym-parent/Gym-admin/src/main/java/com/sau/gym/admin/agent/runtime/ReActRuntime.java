package com.sau.gym.admin.agent.runtime;

/**
 * 作者:hfj
 * 功能:ReAct 执行运行时接口
 * 说明:
 * 负责模型请求、工具调用、结果回传和终止判断，不承担业务路由细节。
 */
public interface ReActRuntime {

    /**
     * 执行一轮 ReAct 循环。
     *
     * @param runContext 已完成会话加载与工具授权的运行上下文
     * @return 结构化运行结果
     */
    AgentRunResult run(AgentRunContext runContext);
}

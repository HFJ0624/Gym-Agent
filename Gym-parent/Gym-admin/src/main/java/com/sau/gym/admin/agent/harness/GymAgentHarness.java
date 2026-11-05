package com.sau.gym.admin.agent.harness;

import com.sau.gym.admin.agent.runtime.AgentRunResult;

/**
 * 作者:hfj
 * 功能:Agent Harness 统一入口
 * 说明:
 * 负责加载会话、安全检查、选择流程、调用运行时、协调拦截器与状态提交。
 * 不承担具体业务细节，也不直接操作 Mapper。
 */
public interface GymAgentHarness {

    /**
     * 执行一轮前台客服请求。
     *
     * @param request Harness 入参
     * @return 结构化运行结果
     */
    AgentRunResult run(AgentHarnessRequest request);
}

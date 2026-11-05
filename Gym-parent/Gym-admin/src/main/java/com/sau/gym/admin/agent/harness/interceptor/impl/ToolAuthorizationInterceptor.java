package com.sau.gym.admin.agent.harness.interceptor.impl;

import com.sau.gym.admin.agent.harness.interceptor.ToolCallRequest;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationChain;
import com.sau.gym.admin.agent.harness.interceptor.ToolInvocationInterceptor;
import com.sau.gym.admin.agent.runtime.AgentRunContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 作者:hfj
 * 功能:工具授权拦截器
 * 职责:
 * 1. 工具必须真实注册，防止模型编造能力。
 * 2. 工具必须在当次授权集合内。
 * 3. 权限拒绝结果不会被后续普通拦截器重新放行。
 */
@Component
public class ToolAuthorizationInterceptor implements ToolInvocationInterceptor {

    private final GymAgentToolRegistry registry;

    public ToolAuthorizationInterceptor(GymAgentToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AgentToolExecuteResult intercept(ToolCallRequest request, ToolInvocationChain chain) {
        String toolCode = request.getToolCode();

        if (!StringUtils.hasText(toolCode)) {
            return AgentToolExecuteResult.paramError("工具名称不能为空。", "toolCode is blank");
        }

        if (!registry.containsTool(toolCode)) {
            return AgentToolExecuteResult.failed(
                    "当前系统没有可用工具：" + toolCode + "。请改用已有工具或直接说明无法查询。",
                    "Tool not registered: " + toolCode
            );
        }

        AgentRunContext runContext = request.getRunContext();

        if (runContext == null || runContext.getAllowedToolCodes() == null
                || !runContext.getAllowedToolCodes().contains(toolCode)) {
            //直接返回权限拒绝，后续拦截器无法把它改回成功。
            return AgentToolExecuteResult.permissionDenied(
                    "本轮未授权使用工具【" + toolCode + "】，已拒绝执行。"
            );
        }

        return chain.proceed(request);
    }

    @Override
    public int order() {
        return 20;
    }
}

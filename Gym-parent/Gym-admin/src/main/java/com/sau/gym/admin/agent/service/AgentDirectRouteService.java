package com.sau.gym.admin.agent.service;

import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;

public interface AgentDirectRouteService {

    /**
     * 尝试处理当前场地预约直达请求。
     * 直达路径直接通过 Registry 执行预约草稿工具，不经过旧的 @Tool 包装层。
     *
     * @param userId          可信用户ID
     * @param sessionId       会话ID
     * @param message         用户原始消息
     * @param venueId         当前有效场馆ID
     * @param courtId         当前有效场地ID
     * @param businessContext 当前业务上下文，可为空
     * @return 命中直达路由时返回工具结果；未命中返回 null
     */
    AgentToolExecuteResult tryHandle(Long userId,
                                     String sessionId,
                                     String message,
                                     Long venueId,
                                     Long courtId,
                                     AgentBusinessContext businessContext);
}

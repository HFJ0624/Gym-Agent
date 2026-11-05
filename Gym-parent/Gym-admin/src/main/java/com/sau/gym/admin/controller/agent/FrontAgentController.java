package com.sau.gym.admin.controller.agent;

import com.sau.gym.admin.agent.security.AgentIdentityMismatchException;
import com.sau.gym.admin.agent.security.AgentTrustedUserResolver;
import com.sau.gym.admin.agent.service.AgentService;
import com.sau.gym.model.dto.agent.AgentChatDto;
import com.sau.gym.model.entity.base.Result;
import com.sau.gym.model.entity.base.ResultCodeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 作者:hfj
 * 功能:agent智能体聊天
 * 日期: 2026/3/30 20:46
 */
@RestController
@RequestMapping(value = "/front/agent")
public class FrontAgentController {

    @Autowired
    private AgentService agentService;

    @Autowired
    private AgentTrustedUserResolver agentTrustedUserResolver;

    /**
     * 前台客服入口。
     * 保留原有 Result 外壳与 query userId 兼容，
     * 但身份以登录态为准，query userId 只做一致性校验。
     */
    @PostMapping("/chat")
    public Result chat(@RequestParam(required = false) Long userId,
                       @RequestHeader(value = "token", required = false) String token,
                       @RequestBody AgentChatDto agentChatDto) {

        Long trustedUserId;

        try {
            trustedUserId = agentTrustedUserResolver.resolve(userId, token);
        } catch (AgentIdentityMismatchException e) {
            //前端传入他人 userId 时直接拒绝，不进入业务查询或工具执行。
            return Result.build(null, ResultCodeEnum.ILLEGAL_REQUEST);
        }

        String reply = agentService.chat(trustedUserId, agentChatDto);

        return Result.build(reply, ResultCodeEnum.SUCCESS);
    }
}

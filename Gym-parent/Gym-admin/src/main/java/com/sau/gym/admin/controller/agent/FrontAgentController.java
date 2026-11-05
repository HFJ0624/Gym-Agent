package com.sau.gym.admin.controller.agent;

import com.sau.gym.admin.agent.runtime.AgentRunResult;
import com.sau.gym.admin.agent.security.AgentIdentityMismatchException;
import com.sau.gym.admin.agent.security.AgentTrustedUserResolver;
import com.sau.gym.admin.agent.service.AgentService;
import com.sau.gym.model.dto.agent.AgentChatDto;
import com.sau.gym.model.dto.agent.AgentChatResponse;
import com.sau.gym.model.entity.base.Result;
import com.sau.gym.model.entity.base.ResultCodeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

/**
 * 作者:hfj
 * 功能:agent智能体聊天
 * 说明:
 * v1 保留原有 Result&lt;String&gt; 外壳与前端兼容；
 * v2 返回结构化 AgentChatResponse（正文、来源、卡片、动作）。
 * 两个接口调用同一个 Harness，一次请求只运行一次 Agent。
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
     * v1 兼容接口：返回字符串（草稿场景为兼容 JSON 文本）。
     */
    @PostMapping("/chat")
    public Result chat(@RequestParam(required = false) Long userId,
                       @RequestHeader(value = "token", required = false) String token,
                       @RequestBody AgentChatDto agentChatDto) {

        AgentRunResult result = resolveAndRun(userId, token, agentChatDto);
        if (result == null) {
            return Result.build(null, ResultCodeEnum.ILLEGAL_REQUEST);
        }

        String reply = StringUtils.hasText(result.getDisplayPayload())
                ? result.getDisplayPayload()
                : result.getReplyText();

        return Result.build(reply, ResultCodeEnum.SUCCESS);
    }

    /**
     * v2 结构化接口：返回 AgentChatResponse。
     */
    @PostMapping("/chat/v2")
    public Result<AgentChatResponse> chatV2(@RequestParam(required = false) Long userId,
                                            @RequestHeader(value = "token", required = false) String token,
                                            @RequestBody AgentChatDto agentChatDto) {

        AgentRunResult result = resolveAndRun(userId, token, agentChatDto);
        if (result == null) {
            return Result.build(null, ResultCodeEnum.ILLEGAL_REQUEST);
        }

        AgentChatResponse response = new AgentChatResponse();
        response.setSessionId(result.getSessionId());
        response.setRunId(result.getRunId());
        response.setStatus(result.getStatus() == null ? null : result.getStatus().name());
        response.setMessage(result.getReplyText());
        response.setSources(result.getSources() == null ? new ArrayList<>() : result.getSources());
        response.setCards(result.getCards() == null ? new ArrayList<>() : result.getCards());
        response.setActions(result.getActions() == null ? new ArrayList<>() : result.getActions());

        return Result.build(response, ResultCodeEnum.SUCCESS);
    }

    /**
     * 解析可信身份并执行一轮 Harness。
     * 身份不一致时返回 null，由调用方返回非法请求。
     */
    private AgentRunResult resolveAndRun(Long userId, String token, AgentChatDto agentChatDto) {
        Long trustedUserId;

        try {
            trustedUserId = agentTrustedUserResolver.resolve(userId, token);
        } catch (AgentIdentityMismatchException e) {
            //前端传入他人 userId 时直接拒绝，不进入业务查询或工具执行。
            return null;
        }

        return agentService.chat(trustedUserId, agentChatDto);
    }
}

package com.sau.gym.admin.agent.service.impl;

import com.sau.gym.admin.agent.harness.AgentHarnessRequest;
import com.sau.gym.admin.agent.harness.GymAgentHarness;
import com.sau.gym.admin.agent.runtime.AgentRunResult;
import com.sau.gym.admin.agent.runtime.AgentRunStatus;
import com.sau.gym.admin.agent.service.AgentService;
import com.sau.gym.admin.agent.service.AgentTraceService;
import com.sau.gym.admin.agent.trace.AgentTraceContext;
import com.sau.gym.admin.agent.trace.AgentTraceInfo;
import com.sau.gym.admin.agent.util.AgentSessionIdUtil;
import com.sau.gym.admin.agent.util.AgentUserContext;
import com.sau.gym.admin.mapper.ChatRecordMapper;
import com.sau.gym.admin.mapper.UserMapper;
import com.sau.gym.model.dto.agent.AgentChatDto;
import com.sau.gym.model.entity.chat.ChatRecord;
import com.sau.gym.model.entity.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 作者:hfj
 * 功能:Agent 主服务
 * 说明:
 * 只负责可信请求上下文、调用 Harness、保存聊天记录和统一收尾，
 * 具体流程编排在 Harness，工具与模型细节在运行时和各自组件中。
 * 日期: 2026/3/10 16:28
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private final GymAgentHarness gymAgentHarness;

    private final ChatRecordMapper chatRecordMapper;

    private final UserMapper userMapper;

    private final AgentTraceService agentTraceService;

    public AgentServiceImpl(GymAgentHarness gymAgentHarness,
                            ChatRecordMapper chatRecordMapper,
                            UserMapper userMapper,
                            AgentTraceService agentTraceService) {
        this.gymAgentHarness = gymAgentHarness;
        this.chatRecordMapper = chatRecordMapper;
        this.userMapper = userMapper;
        this.agentTraceService = agentTraceService;
    }

    @Override
    public AgentRunResult chat(Long userId, AgentChatDto agentChatDto) {
        if (agentChatDto == null || !StringUtils.hasText(agentChatDto.getMessage())) {
            throw new RuntimeException("消息不能为空");
        }

        String message = agentChatDto.getMessage().trim();

        //sessionId 跨轮稳定，runId/traceId 每轮独立，二者不能混用。
        String sessionId = AgentSessionIdUtil.normalize(agentChatDto.getSessionId());
        String runId = UUID.randomUUID().toString().replace("-", "");

        long start = System.currentTimeMillis();

        AgentTraceContext.set(new AgentTraceInfo(runId, userId, message));
        AgentUserContext.setUserId(userId);

        try {
            agentTraceService.startTrace(runId, userId, null, message);

            AgentHarnessRequest request = AgentHarnessRequest.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .runId(runId)
                    .message(message)
                    .chatDto(agentChatDto)
                    .build();

            AgentRunResult result = gymAgentHarness.run(request);

            String reply = StringUtils.hasText(result.getDisplayPayload())
                    ? result.getDisplayPayload()
                    : result.getReplyText();

            //聊天记录使用稳定的 sessionId，不再每轮生成新会话标识。
            saveChatRecord(userId, sessionId, message, result.getReplyText());

            finishTrace(runId, result, reply, System.currentTimeMillis() - start);

            result.setSessionId(sessionId);
            result.setRunId(runId);

            return result;

        } catch (Exception e) {
            log.error("[AgentService] 处理失败, sessionId={}", sessionId, e);

            String reply = "AI服务异常，请稍后再试。";

            saveChatRecord(userId, sessionId, message, reply);

            try {
                agentTraceService.addStep("AGENT_SERVICE_FAILED", "Agent 主服务异常",
                        message, null, "FAILED", e.getMessage(),
                        System.currentTimeMillis() - start);

                agentTraceService.finishFailed(runId, reply, e.getMessage(),
                        System.currentTimeMillis() - start);
            } catch (Exception traceException) {
                //Trace 失败不能影响用户正常返回。
                log.warn("[AgentService] Trace 收尾失败: {}", traceException.getMessage());
            }

            AgentRunResult failedResult = AgentRunResult.failed(reply, "AGENT_SERVICE_ERROR");
            failedResult.setSessionId(sessionId);
            failedResult.setRunId(runId);
            failedResult.setReplyText(reply);

            return failedResult;

        } finally {
            AgentTraceContext.clear();
            AgentUserContext.clear();
        }
    }

    /**
     * 根据运行终态结束 Trace。
     */
    private void finishTrace(String runId, AgentRunResult result, String reply, long costMs) {
        try {
            if (result.getStatus() == AgentRunStatus.COMPLETED
                    || result.getStatus() == AgentRunStatus.NEED_CLARIFICATION
                    || result.getStatus() == AgentRunStatus.WAITING_CONFIRMATION) {
                agentTraceService.finishSuccess(runId, reply, costMs);
                return;
            }

            agentTraceService.finishFailed(
                    runId,
                    reply,
                    result.getErrorCode() == null ? result.getStatus().name() : result.getErrorCode(),
                    costMs
            );
        } catch (Exception e) {
            //Trace 收尾失败不影响用户回复。
            log.warn("[AgentService] Trace 收尾失败: {}", e.getMessage());
        }
    }

    /**
     * 保存聊天记录。
     * 未登录请求没有可信用户ID，不写入以用户为维度的聊天记录表。
     */
    private void saveChatRecord(Long userId, String sessionId, String userMessage, String aiReply) {
        if (userId == null) {
            return;
        }

        try {
            ChatRecord record = new ChatRecord();
            record.setUserId(userId);

            User user = userMapper.selectById(userId);
            if (user != null) {
                record.setUsername(user.getUsername());
            }

            record.setSessionId(sessionId);
            record.setUserMessage(userMessage);
            record.setAiReply(aiReply == null ? "" : aiReply);
            record.setCreateTime(LocalDateTime.now());

            chatRecordMapper.insertChatRecord(record);
        } catch (Exception e) {
            //聊天记录写入失败不影响用户对话，但必须可观察。
            log.warn("[AgentService] 保存聊天记录失败, sessionId={}, reason={}", sessionId, e.getMessage());
        }
    }
}

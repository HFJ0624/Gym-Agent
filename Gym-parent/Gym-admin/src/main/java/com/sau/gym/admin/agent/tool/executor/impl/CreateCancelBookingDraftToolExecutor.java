package com.sau.gym.admin.agent.tool.executor.impl;

import com.sau.gym.admin.agent.model.AgentDraftCreationResult;
import com.sau.gym.admin.agent.service.AgentCancelBookingService;
import com.sau.gym.admin.agent.service.AgentToolGuardService;
import com.sau.gym.admin.agent.tool.AgentToolCodes;
import com.sau.gym.admin.agent.tool.executor.AbstractGymAgentToolExecutor;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteContext;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import com.sau.gym.admin.agent.tool.executor.AgentToolParamDefinition;
import com.sau.gym.admin.enums.AgentRiskLevel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 作者:hfj
 * 功能:生成取消预约草稿工具执行器
 * 注意:
 * 1. 这个工具只生成取消预约草稿。
 * 2. 不会真正取消预约。
 * 3. 用户后续确认后，才由 AgentServiceImpl 的确认流程真正执行取消。
 * 日期: 2026/5/27 20:47
 */
@Component
public class CreateCancelBookingDraftToolExecutor extends AbstractGymAgentToolExecutor {

    private final AgentCancelBookingService cancelBookingService;

    public CreateCancelBookingDraftToolExecutor(
            AgentToolGuardService agentToolGuardService,
            AgentCancelBookingService cancelBookingService
    ) {
        super(agentToolGuardService);
        this.cancelBookingService = cancelBookingService;
    }

    @Override
    public String toolCode() {
        return AgentToolCodes.CREATE_CANCEL_BOOKING_DRAFT;
    }

    @Override
    public String toolName() {
        return "生成取消预约草稿";
    }

    @Override
    public String description() {
        return "根据预约ID生成取消预约草稿，不会真正取消。用户确认后才会执行真实取消预约。";
    }

    @Override
    public AgentRiskLevel riskLevel() {
        return AgentRiskLevel.MEDIUM;
    }

    @Override
    public boolean needLogin() {
        return true;
    }

    @Override
    public boolean needConfirm() {
        // 这里只是生成草稿，不是真正取消预约，所以不在这里拦确认。
        return false;
    }

    @Override
    public int rateLimitSeconds() {
        return 3;
    }

    @Override
    public List paramDefinitions() {
        return Arrays.asList(
                new AgentToolParamDefinition(
                        "bookingId",
                        "要取消的预约ID",
                        "Long",
                        true,
                        "12"
                ),
                new AgentToolParamDefinition(
                        "reason",
                        "取消原因",
                        "String",
                        false,
                        "用户通过Agent取消预约"
                )
        );
    }

    @Override
    protected AgentToolExecuteResult doExecute(AgentToolExecuteContext context) {
        Long userId = context.getUserId();
        Long bookingId = context.getLongParam("bookingId");
        String reason = context.getStringParam("reason");

        if (!StringUtils.hasText(reason)) {
            reason = "用户通过Agent取消预约";
        }

        AgentDraftCreationResult draft = cancelBookingService.createCancelBookingDraft(
                userId,
                context.getSessionId(),
                bookingId,
                reason
        );

        //草稿创建失败时不能返回 NEED_CONFIRM，否则前端会展示一张无效卡片。
        if (!StringUtils.hasText(draft.getConfirmToken())) {
            return AgentToolExecuteResult.failed(draft.getText(), "cancel draft not created");
        }

        AgentToolExecuteResult result = AgentToolExecuteResult.needConfirm(
                draft.getText(),
                draft.getData(),
                "confirm_cancel_booking"
        );

        //确认码必须来自后端草稿，不能让模型重新生成。
        result.addExtra("confirmToken", draft.getConfirmToken());

        if (draft.getData() != null) {
            result.addExtra("bookingId", draft.getData().get("bookingId"));
        }

        return result;
    }
}

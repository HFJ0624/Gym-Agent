package com.sau.gym.admin.agent.workflow;

import com.sau.gym.admin.agent.intent.IntentRouteResult;
import com.sau.gym.admin.enums.AgentIntent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作者:hfj
 * 功能:轻量流程路由
 * 说明:
 * 1. 确认与取消当前待办优先处理，不进入模型自由选择工具集合。
 * 2. 明确的当前场地预约走直达路径。
 * 3. 信息不足直接追问。
 * 4. 其余走 ReAct。
 */
@Component
public class AgentWorkflowRouter {

    /**
     * “取消预约 12”这类固定指令，用于直接生成取消草稿。
     * 注意与“确认取消预约 123456”区分，后者是确认动作。
     */
    private static final Pattern CANCEL_BOOKING_COMMAND =
            Pattern.compile("^取消预约\\s*(\\d{1,18})$");

    /**
     * 选择本轮流程。
     *
     * @param message     用户原始输入
     * @param intentRoute 意图识别结果，可为空
     */
    public WorkflowDecision decide(String message, IntentRouteResult intentRoute) {
        String text = message == null ? "" : message.trim();

        if (!StringUtils.hasText(text)) {
            return WorkflowDecision.of(WorkflowType.CLARIFY, "空消息")
                    .with("question", "请描述你想咨询或办理的业务。");
        }

        //1. 确认类指令优先，避免被模型自由发挥。
        if (text.startsWith("确认取消预约") || text.startsWith("确认取消")) {
            return WorkflowDecision.of(WorkflowType.CONFIRMATION, "确认取消预约指令")
                    .with("command", "confirm_cancel_booking")
                    .with("token", text.replaceFirst("^确认取消预约|^确认取消", "").trim());
        }

        if (text.startsWith("确认预约")) {
            return WorkflowDecision.of(WorkflowType.CONFIRMATION, "确认预约指令")
                    .with("command", "confirm_booking")
                    .with("token", text.substring("确认预约".length()).trim());
        }

        if (text.startsWith("确认下单")) {
            return WorkflowDecision.of(WorkflowType.CONFIRMATION, "确认下单指令")
                    .with("command", "confirm_shopping")
                    .with("token", text.substring("确认下单".length()).trim());
        }

        if ("取消".equals(text) || "取消操作".equals(text)) {
            return WorkflowDecision.of(WorkflowType.CONFIRMATION, "取消当前待确认草稿")
                    .with("command", "clear_draft");
        }

        //2. 指定预约ID的取消草稿指令。
        Matcher matcher = CANCEL_BOOKING_COMMAND.matcher(text);
        if (matcher.matches()) {
            return WorkflowDecision.of(WorkflowType.CANCEL_BOOKING_DRAFT, "指定预约ID生成取消草稿")
                    .with("bookingId", matcher.group(1));
        }

        if (intentRoute == null) {
            return WorkflowDecision.of(WorkflowType.REACT, "无意图结果，交给 ReAct");
        }

        //3. 信息不足直接追问，不调用模型。
        if (intentRoute.isNeedClarify()) {
            return WorkflowDecision.of(WorkflowType.CLARIFY, "意图识别判定信息不足")
                    .with("question", intentRoute.getClarifyQuestion());
        }

        //4. 明确的当前场地预约先尝试直达，失败后回落 ReAct。
        if (intentRoute.getIntent() == AgentIntent.BOOKING_DRAFT) {
            return WorkflowDecision.of(WorkflowType.DIRECT_ROUTE, "预约意图，尝试当前场地直达");
        }

        return WorkflowDecision.of(WorkflowType.REACT, "常规 ReAct 路径");
    }
}

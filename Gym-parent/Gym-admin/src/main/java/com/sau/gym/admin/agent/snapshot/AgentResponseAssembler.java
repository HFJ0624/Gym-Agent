package com.sau.gym.admin.agent.snapshot;

import com.sau.gym.admin.agent.store.PendingDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:Agent 回复卡片/动作组装器
 * 说明:
 * v2 聊天回复与会话恢复快照共用同一套卡片和动作组装，
 * 避免两处拼出不一致的结构。数据全部来自真实 PendingDraft。
 */
@Component
public class AgentResponseAssembler {

    /**
     * 由真实草稿构造卡片。
     */
    public Map<String, Object> buildCard(PendingDraft draft) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", draft.type().name());
        card.put("data", draft.data());
        card.put("confirmToken", draft.confirmToken());
        return card;
    }

    /**
     * 由真实草稿构造允许操作。
     */
    public List<Map<String, Object>> buildActions(PendingDraft draft) {
        List<Map<String, Object>> actions = new ArrayList<>();

        String confirmLabel = switch (draft.type()) {
            case BOOKING -> "确认预约";
            case SHOPPING -> "确认下单";
            case CANCEL_BOOKING -> "确认取消预约";
        };

        Map<String, Object> confirmAction = new LinkedHashMap<>();
        confirmAction.put("type", "confirm");
        confirmAction.put("label", confirmLabel);
        confirmAction.put("command", confirmLabel + " " + draft.confirmToken());
        actions.add(confirmAction);

        Map<String, Object> cancelAction = new LinkedHashMap<>();
        cancelAction.put("type", "cancel");
        cancelAction.put("label", "取消");
        cancelAction.put("command", "取消");
        actions.add(cancelAction);

        return actions;
    }
}

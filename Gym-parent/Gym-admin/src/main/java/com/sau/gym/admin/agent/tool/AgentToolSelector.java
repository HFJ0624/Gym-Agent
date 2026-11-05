package com.sau.gym.admin.agent.tool;

import com.sau.gym.admin.agent.tool.registry.GymAgentToolRegistry;
import com.sau.gym.admin.enums.AgentIntent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 作者:hfj
 * 功能:轻量工具选择器
 * 说明:
 * 按场景减少暴露给模型的 Schema 数量，降低误调用概率。
 * 这里是“减少 Schema”，真正的执行授权还会在工具拦截器中再检查一次。
 */
@Component
public class AgentToolSelector {

    /**
     * 知识咨询、规则、停车、开放时间等场景。
     */
    private static final Set<String> KNOWLEDGE_TOOLS = setOf(
            AgentToolCodes.ASK_GYM_KNOWLEDGE,
            AgentToolCodes.QUERY_VENUE_LIST,
            AgentToolCodes.QUERY_NOTICE_LIST
    );

    /**
     * 预约协助场景。
     * 目前没有实时场次查询执行器，因此不暴露相关逻辑工具。
     */
    private static final Set<String> BOOKING_TOOLS = setOf(
            AgentToolCodes.CREATE_BOOKING_DRAFT,
            AgentToolCodes.ASK_GYM_KNOWLEDGE,
            AgentToolCodes.QUERY_VENUE_LIST,
            AgentToolCodes.QUERY_NOTICE_LIST
    );

    /**
     * 取消协助场景。
     */
    private static final Set<String> CANCEL_TOOLS = setOf(
            AgentToolCodes.QUERY_CANCELABLE_BOOKING,
            AgentToolCodes.CREATE_CANCEL_BOOKING_DRAFT,
            AgentToolCodes.ASK_GYM_KNOWLEDGE
    );

    /**
     * 商品协助场景。
     */
    private static final Set<String> SHOPPING_TOOLS = setOf(
            AgentToolCodes.CREATE_SHOPPING_DRAFT,
            AgentToolCodes.ASK_GYM_KNOWLEDGE
    );

    /**
     * 闲聊场景，尽量不给工具。
     */
    private static final Set<String> SMALL_TALK_TOOLS = setOf();

    private final GymAgentToolRegistry registry;

    public AgentToolSelector(GymAgentToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 按意图选择本轮可用工具。
     *
     * @param intent  意图识别结果，可为空
     * @param message 用户原始输入，用于补充关键词判断
     * @return 已过滤为真实注册工具的有序集合
     */
    public LinkedHashSet<String> select(AgentIntent intent, String message) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        if (intent == null) {
            candidates.addAll(allImplementedTools());
            return filterImplemented(candidates);
        }

        switch (intent) {
            case RAG_KNOWLEDGE, VENUE_QUERY, COURT_QUERY, SLOT_QUERY, ORDER_QUERY ->
                    candidates.addAll(KNOWLEDGE_TOOLS);

            case BOOKING_DRAFT -> candidates.addAll(BOOKING_TOOLS);

            case BOOKING_CANCEL -> candidates.addAll(CANCEL_TOOLS);

            case SHOPPING_QUERY -> {
                //商品咨询同时可能需要规则说明，保留知识工具。
                candidates.addAll(SHOPPING_TOOLS);
            }

            case SMALL_TALK -> candidates.addAll(SMALL_TALK_TOOLS);

            default -> candidates.addAll(allImplementedTools());
        }

        //组合问题:一句话里同时出现预约和取消、或预约和商品时，补齐对应工具。
        if (StringUtils.hasText(message)) {
            String text = message;

            if (text.contains("取消") || text.contains("退款")) {
                candidates.addAll(CANCEL_TOOLS);
            }

            if (text.contains("预约") || text.contains("订场") || text.contains("预订")) {
                candidates.add(AgentToolCodes.CREATE_BOOKING_DRAFT);
            }

            if (text.contains("买") || text.contains("商品") || text.contains("下单")) {
                candidates.add(AgentToolCodes.CREATE_SHOPPING_DRAFT);
            }
        }

        return filterImplemented(candidates);
    }

    /**
     * 所有真实注册过的工具。
     */
    private Set<String> allImplementedTools() {
        LinkedHashSet<String> all = new LinkedHashSet<>();

        registry.listTools().forEach(info -> {
            if (info != null && info.isEnabled()) {
                all.add(info.getToolCode());
            }
        });

        return all;
    }

    /**
     * 过滤掉没有执行器的逻辑工具，避免把不存在的能力暴露给模型。
     */
    private LinkedHashSet<String> filterImplemented(Set<String> candidates) {
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (String code : candidates) {
            if (registry.containsTool(code)) {
                result.add(code);
            }
        }

        return result;
    }

    /**
     * 构造不可变集合。
     */
    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}

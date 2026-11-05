package com.sau.gym.admin.agent.tool.executor;

import com.sau.gym.admin.agent.memory.model.AgentBusinessContext;
import com.sau.gym.admin.enums.AgentIntent;
import com.sau.gym.admin.rag.retrieval.EvidenceRegistry;
import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 作者:hfj
 * 功能: Agent 工具执行上下文
 * 作用:
 * 每次调用工具时，把用户、意图、上下文、参数等信息统一封装起来。
 * 日期: 2026/5/27 13:54
 */
@Data
public class AgentToolExecuteContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前 traceId。
     * 作用:
     * 用于关联一次完整 Agent 调用链路。
     */
    private String traceId;

    /**
     * 本次运行的 runId。
     * traceId 与 runId 每轮独立，sessionId 跨轮稳定，三者不能混用。
     */
    private String runId;

    /**
     * 当前会话ID。
     * 草稿、业务上下文和会话历史都按 userId + sessionId 维度隔离。
     */
    private String sessionId;

    /**
     * 本轮剩余预算毫秒数。
     * 工具可据此决定是否还能发起耗时的外部查询。
     */
    private Long remainingBudgetMs;

    /**
     * 当前用户ID。
     * 需要登录的工具必须检查这个字段。
     */
    private Long userId;

    /**
     * 用户原始问题。
     */
    private String originalQuestion;

    /**
     * 系统重写后的问题。
     */
    private String rewrittenQuestion;

    /**
     * 当前识别出的用户意图。
     */
    private AgentIntent intent;

    /**
     * 当前业务上下文。
     * 里面包含最近场馆、最近场地、预约日期、开始时间、结束时间等结构化槽位。
     */
    private AgentBusinessContext businessContext;

    /**
     * 本次运行的证据登记上下文。
     * 知识检索工具把命中的证据登记进来并分配 K1、K2 引用标签，
     * 最终回答的引用合法性由后端基于该上下文校验。
     */
    private transient EvidenceRegistry evidenceRegistry;

    /**
     * 工具入参。
     */
    private Map<String, Object> params = new HashMap<>();

    /**
     * 扩展信息。
     */
    private Map<String, Object> extra = new HashMap<>();

    /**
     * 从 params 中读取 String 类型参数。
     */
    public String getStringParam(String key) {
        Object value = getParams().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 从 params 中读取 Long 类型参数。
     */
    public Long getLongParam(String key) {
        Object value = getParams().get(key);

        if (value == null) {
            return null;
        }

        if (value instanceof Long) {
            return (Long) value;
        }

        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }

        try {
            return Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 params 中读取 Integer 类型参数。
     */
    public Integer getIntegerParam(String key) {
        Object value = getParams().get(key);

        if (value == null) {
            return null;
        }

        if (value instanceof Integer) {
            return (Integer) value;
        }

        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 添加参数。
     *
     * @param key 参数名
     * @param value 参数值
     * @return 当前对象，方便链式调用
     */
    public AgentToolExecuteContext addParam(String key, Object value) {
        this.params.put(key, value);
        return this;
    }

    /**
     * 添加扩展字段。
     *
     * @param key 字段名
     * @param value 字段值
     * @return 当前对象，方便链式调用
     */
    public AgentToolExecuteContext addExtra(String key, Object value) {
        this.extra.put(key, value);
        return this;
    }
}

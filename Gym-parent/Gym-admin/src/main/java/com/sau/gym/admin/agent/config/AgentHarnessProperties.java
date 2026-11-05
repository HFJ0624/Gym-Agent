package com.sau.gym.admin.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 作者:hfj
 * 功能:自建 Harness 的运行预算配置
 * 说明:
 * 这里的默认值是本轮建议值，不是通用标准或压测结论。
 * 配置缺省时仍然可以启动，便于老环境平滑升级。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gym.agent.harness")
public class AgentHarnessProperties {

    /**
     * 单轮最多发起多少次 Agent 模型请求（含失败重试计数）。
     * 注意: RAG 工具内部的模型调用不计入该预算。
     */
    private int maxAgentModelCalls = 6;

    /**
     * 单轮最多派发多少次工具调用（按派发尝试计数，包含被拒绝的调用）。
     */
    private int maxToolCalls = 8;

    /**
     * 单轮最长耗时，单位毫秒。
     */
    private long maxRunDurationMs = 60000L;

    /**
     * 上下文 Token 估算上限。
     * 这里是按字符构成估算的值，不是服务端精确计数。
     */
    private int maxContextTokens = 12000;

    /**
     * 单条工具结果写入模型上下文前的最大字符数。
     */
    private int maxToolResultChars = 6000;

    /**
     * 相同工具 + 相同参数的最大执行次数，达到后停止重复执行。
     */
    private int repeatToolThreshold = 3;

    /**
     * 会话并发锁的有效期，单位毫秒。
     */
    private long sessionLockTtlMs = 60000L;
}

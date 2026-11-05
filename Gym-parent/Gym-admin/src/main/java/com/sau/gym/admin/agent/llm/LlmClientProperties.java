package com.sau.gym.admin.agent.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 作者:hfj
 * 功能:自建 LlmClient 的配置项
 * 说明:
 * 复用项目已有的 gym.ai.base-url / api-key / model-name / temperature，
 * 新增的超时、输出长度等参数都带向后兼容默认值，不配置也能启动。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gym.ai")
public class LlmClientProperties {

    /**
     * 模型服务基础地址。
     * 示例: https://ark.cn-beijing.volces.com/api/v3
     */
    private String baseUrl;

    /**
     * 模型服务鉴权 Key。
     * 注意: 该字段只用于请求头，不允许写入日志。
     */
    private String apiKey;

    /**
     * 模型名称。
     */
    private String modelName;

    /**
     * 采样温度，越低越稳定。
     */
    private Double temperature = 0.1D;

    /**
     * 建立 TCP 连接的毫秒超时。
     */
    private int connectTimeoutMs = 5000;

    /**
     * 单次模型请求的毫秒超时上限。
     * 真正生效的时间还会被本轮剩余预算裁剪，取两者较小值。
     */
    private int requestTimeoutMs = 45000;

    /**
     * 单次模型输出的最大 Token 数，防止超长输出拖垮本轮预算。
     */
    private int maxOutputTokens = 1024;
}

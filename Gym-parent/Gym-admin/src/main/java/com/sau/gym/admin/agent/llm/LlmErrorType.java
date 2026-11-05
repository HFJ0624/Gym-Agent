package com.sau.gym.admin.agent.llm;

/**
 * 作者:hfj
 * 功能:模型调用错误分类
 * 说明:
 * 不用“异常信息里是否包含 timed out”这种字符串判断来决定错误处理策略，
 * 由客户端在抛出异常时给出明确类型。
 */
public enum LlmErrorType {

    /**
     * 配置缺失，例如 api-key 为空。
     */
    CONFIG_ERROR,

    /**
     * 鉴权失败，HTTP 401 / 403。
     */
    AUTH_ERROR,

    /**
     * 被模型服务限流，HTTP 429。
     */
    RATE_LIMITED,

    /**
     * 模型服务端错误，HTTP 5xx。
     */
    SERVER_ERROR,

    /**
     * 其他非成功 HTTP 状态。
     */
    HTTP_ERROR,

    /**
     * 连接或请求超时。
     */
    TIMEOUT,

    /**
     * 网络异常。
     */
    NETWORK_ERROR,

    /**
     * 响应体解析失败或结构不合法。
     */
    PARSE_ERROR,

    /**
     * 模型返回空响应，没有任何可用内容。
     */
    EMPTY_RESPONSE,

    /**
     * 当前线程被中断。
     */
    CANCELLED
}

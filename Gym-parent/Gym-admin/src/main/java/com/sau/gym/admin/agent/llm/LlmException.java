package com.sau.gym.admin.agent.llm;

/**
 * 作者:hfj
 * 功能:模型调用异常
 * 说明:
 * 携带明确的错误类型，供拦截器和运行时决定终止还是降级。
 * 异常 message 只保留可读原因，绝不拼接 api-key。
 */
public class LlmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误类型。
     */
    private final LlmErrorType errorType;

    /**
     * 是否属于可重试的瞬时故障。
     * 本轮不自动重试，该标记只用于 Trace 与后续扩展。
     */
    private final boolean retryable;

    public LlmException(LlmErrorType errorType, String message) {
        this(errorType, message, null);
    }

    public LlmException(LlmErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.retryable = errorType == LlmErrorType.TIMEOUT
                || errorType == LlmErrorType.NETWORK_ERROR
                || errorType == LlmErrorType.RATE_LIMITED
                || errorType == LlmErrorType.SERVER_ERROR;
    }

    public LlmErrorType getErrorType() {
        return errorType;
    }

    public boolean isRetryable() {
        return retryable;
    }
}

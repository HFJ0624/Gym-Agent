package com.sau.gym.admin.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 作者:hfj
 * 功能:基于 Java 17 HttpClient 的 OpenAI-compatible 模型客户端
 * 说明:
 * 1. 直接拼 /chat/completions，避免 base-url 已带 /v1 或已带 /chat/completions 时重复拼接。
 * 2. 只发送本项目真实使用的协议字段。
 * 3. 日志中不输出 api-key、Authorization 等凭据。
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    /**
     * 协议端点后缀。
     */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    /**
     * 错误响应体在日志/异常中保留的最大字符数。
     */
    private static final int MAX_ERROR_BODY_CHARS = 300;

    private final LlmClientProperties properties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public OpenAiCompatibleLlmClient(LlmClientProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        int connectTimeout = properties.getConnectTimeoutMs() > 0
                ? properties.getConnectTimeoutMs()
                : 5000;

        //连接超时可配置，请求超时按每次调用单独设置。
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();
    }

    @Override
    public LlmChatResponse chat(LlmChatRequest request) {
        if (request == null) {
            throw new LlmException(LlmErrorType.CONFIG_ERROR, "模型请求不能为空");
        }

        if (!StringUtils.hasText(properties.getBaseUrl())) {
            throw new LlmException(LlmErrorType.CONFIG_ERROR, "未配置 gym.ai.base-url");
        }

        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new LlmException(LlmErrorType.CONFIG_ERROR, "未配置 gym.ai.api-key");
        }

        //补齐模型名等公共字段，调用方只关心本次消息与工具。
        if (!StringUtils.hasText(request.getModel())) {
            request.setModel(properties.getModelName());
        }

        if (request.getTemperature() == null) {
            request.setTemperature(properties.getTemperature());
        }

        if (request.getMaxTokens() == null && properties.getMaxOutputTokens() > 0) {
            request.setMaxTokens(properties.getMaxOutputTokens());
        }

        long timeoutMs = resolveTimeoutMs(request.getTimeoutMs());

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new LlmException(LlmErrorType.PARSE_ERROR, "模型请求序列化失败：" + e.getMessage(), e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(buildChatCompletionsUri())
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        long start = System.currentTimeMillis();

        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        } catch (HttpTimeoutException e) {
            throw new LlmException(
                    LlmErrorType.TIMEOUT,
                    "模型请求超时，已等待 " + timeoutMs + " 毫秒",
                    e
            );
        } catch (InterruptedException e) {
            //恢复中断标记，交给上层决定终止。
            Thread.currentThread().interrupt();
            throw new LlmException(LlmErrorType.CANCELLED, "模型请求被中断", e);
        } catch (Exception e) {
            throw new LlmException(LlmErrorType.NETWORK_ERROR, "模型网络请求失败：" + e.getMessage(), e);
        }

        long cost = System.currentTimeMillis() - start;
        int status = httpResponse.statusCode();

        //只记录必要元信息，不打印请求头与请求体，避免凭据和用户隐私落日志。
        log.info("[LlmClient] model={}, messages={}, tools={}, status={}, cost={}ms",
                request.getModel(),
                request.getMessages() == null ? 0 : request.getMessages().size(),
                request.getTools() == null ? 0 : request.getTools().size(),
                status,
                cost);

        if (status < 200 || status >= 300) {
            throw buildHttpException(status, httpResponse.body());
        }

        String body = httpResponse.body();

        if (!StringUtils.hasText(body)) {
            throw new LlmException(LlmErrorType.EMPTY_RESPONSE, "模型返回空响应体");
        }

        LlmChatResponse response;
        try {
            response = objectMapper.readValue(body, LlmChatResponse.class);
        } catch (Exception e) {
            throw new LlmException(LlmErrorType.PARSE_ERROR, "模型响应解析失败：" + e.getMessage(), e);
        }

        List<LlmChoice> choices = response.getChoices();
        if (choices == null || choices.isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_RESPONSE, "模型响应中没有候选结果");
        }

        return response;
    }

    /**
     * 计算本次请求超时。
     * 上层传入的剩余预算更小的时候以预算为准，避免等待超过本轮截止时间。
     */
    private long resolveTimeoutMs(Integer requestedTimeoutMs) {
        int configured = properties.getRequestTimeoutMs() > 0 ? properties.getRequestTimeoutMs() : 45000;

        long timeout = configured;

        if (requestedTimeoutMs != null && requestedTimeoutMs > 0) {
            timeout = Math.min(configured, requestedTimeoutMs);
        }

        //至少保留 1 秒，避免极端情况下传 0 或负数导致请求必然失败。
        return Math.max(timeout, 1000L);
    }

    /**
     * 根据错误状态码构造分类明确的异常。
     */
    private LlmException buildHttpException(int status, String body) {
        String bodySnippet = truncate(body, MAX_ERROR_BODY_CHARS);
        String message = "模型服务返回 HTTP " + status + "：" + bodySnippet;

        if (status == 401 || status == 403) {
            return new LlmException(LlmErrorType.AUTH_ERROR, message);
        }

        if (status == 429) {
            return new LlmException(LlmErrorType.RATE_LIMITED, message);
        }

        if (status >= 500) {
            return new LlmException(LlmErrorType.SERVER_ERROR, message);
        }

        return new LlmException(LlmErrorType.HTTP_ERROR, message);
    }

    /**
     * 拼接 chat/completions 地址。
     * 规则:
     * 1. 已包含 /chat/completions 时原样使用。
     * 2. 其余情况去掉末尾斜杠后追加 /chat/completions。
     * 这样 base-url 是 .../api/v3 或 .../v1 都不会重复拼 /v1。
     */
    private URI buildChatCompletionsUri() {
        String baseUrl = properties.getBaseUrl().trim();

        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        if (!baseUrl.endsWith(CHAT_COMPLETIONS_PATH)) {
            baseUrl = baseUrl + CHAT_COMPLETIONS_PATH;
        }

        return URI.create(baseUrl);
    }

    /**
     * 截断文本，避免异常信息过长。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }

        String value = text.trim();

        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}

package com.sau.gym.admin.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sau.gym.admin.agent.tool.executor.AgentToolExecuteResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:工具结果压缩器
 * 说明:
 * 工具结果要回传给模型，需要:
 * 1. 只保留结构化关键字段，避免把整个对象图塞进上下文。
 * 2. 长文本按字段限长，而不是从中间截断 JSON。
 * 3. 确认码、草稿标识、错误码等关键字段不能被裁剪掉。
 */
@Component
public class ToolResultCompactor {

    /**
     * 普通字符串字段的最大长度。
     */
    private static final int MAX_TEXT_CHARS = 1500;

    /**
     * 用户可见消息的最大长度。
     * 草稿文案需要完整展示给前端，因此比普通字段宽松。
     */
    private static final int MAX_MESSAGE_CHARS = 2200;

    private final ObjectMapper objectMapper;

    public ToolResultCompactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把工具结果压缩成可回传模型的 JSON 字符串。
     *
     * @param result   工具执行结果
     * @param maxChars 整体最大字符数
     */
    public String compact(AgentToolExecuteResult result, int maxChars) {
        if (result == null) {
            return "{\"status\":\"FAILED\",\"success\":false,\"message\":\"工具未返回结果\"}";
        }

        //关键字段放在前面，保证即使整体超长也能优先保留。
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("status", result.getStatus() == null ? null : result.getStatus().name());
        compact.put("success", result.isSuccess());
        compact.put("message", limit(result.getMessage(), MAX_MESSAGE_CHARS));
        compact.put("needConfirm", result.isNeedConfirm());
        compact.put("confirmAction", result.getConfirmAction());

        if (result.getErrorCode() != null) {
            compact.put("errorCode", result.getErrorCode());
        }

        if (result.getExtra() != null && !result.getExtra().isEmpty()) {
            compact.put("extra", result.getExtra());
        }

        compact.put("data", compactValue(result.getData(), MAX_TEXT_CHARS));

        String json = toJson(compact);

        if (json.length() <= maxChars) {
            return json;
        }

        //仍然超长时丢弃长文本 data，但保留 message 与 extra 中的关键状态。
        compact.put("data", "（内容过长已省略，请以 message 与 extra 中的状态为准）");

        String fallback = toJson(compact);

        if (fallback.length() <= maxChars) {
            return fallback;
        }

        //最后兜底：只保留最关键的状态字段。
        Map<String, Object> minimal = new LinkedHashMap<>();
        minimal.put("status", compact.get("status"));
        minimal.put("success", compact.get("success"));
        minimal.put("message", limit(result.getMessage(), 500));
        minimal.put("needConfirm", compact.get("needConfirm"));
        minimal.put("confirmAction", compact.get("confirmAction"));
        minimal.put("extra", compact.get("extra"));

        return toJson(minimal);
    }

    /**
     * 递归压缩值中的长字符串。
     */
    private Object compactValue(Object value, int maxChars) {
        if (value == null) {
            return null;
        }

        if (value instanceof String text) {
            return limit(text, maxChars);
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), compactValue(item, maxChars)));
            return copy;
        }

        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            iterable.forEach(item -> list.add(compactValue(item, maxChars)));
            return list;
        }

        //数字、布尔、枚举等直接保留。
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return value;
        }

        //其他对象转成字符串，避免序列化大对象图。
        return limit(String.valueOf(value), maxChars);
    }

    /**
     * 限制字符串长度。
     */
    private String limit(String text, int maxChars) {
        if (text == null) {
            return null;
        }

        String value = text.trim();

        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...(已截断)";
    }

    /**
     * 序列化，失败时返回最小合法 JSON。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"status\":\"FAILED\",\"success\":false,\"message\":\"工具结果序列化失败\"}";
        }
    }
}

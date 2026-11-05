package com.sau.gym.admin.agent.memory.model;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 作者:hfj
 * 功能:会话历史快照
 * 说明:
 * 1. messages 是裁剪后的协议消息（不含系统提示词）。
 * 2. summary 是被移出的较早轮次生成的摘要；messages 里不再包含被摘要的内容。
 * 3. summaryVersion 每次摘要成功后递增，用于 Trace 与排查。
 * 4. summary 由模型生成，仅用于理解上下文；业务结构化状态（场馆/场地ID、
 *    日期时段、草稿与确认状态）仍以 Redis 权威字段为准，不会被摘要覆盖。
 */
@Data
public class SessionHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 历史摘要，可为空。
     */
    private String summary;

    /**
     * 摘要版本号。
     */
    private int summaryVersion;

    /**
     * 会话协议消息。
     */
    private List<SessionMessage> messages = new ArrayList<>();
}

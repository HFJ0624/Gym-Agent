package com.sau.gym.admin.agent.memory.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * 3. summaryVersion 每次摘要成功后递增；revision 是状态提交版本（每次成功提交 +1），
 *    二者语义不同，不能混用。
 * 4. summary 由模型生成，仅用于理解上下文；业务结构化状态（场馆/场地ID、
 *    日期时段、草稿与确认状态）仍以 Redis 权威字段为准，不会被摘要覆盖。
 * 5. displayTurns 保存面向前端的展示轮次（正文/来源/状态），与协议消息区分；
 *    旧快照没有该字段时按协议消息受控提取。
 * 6. loadFailed/loadError 是读取时的诊断标记，不参与持久化语义。
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
     * 状态提交版本号，每次成功提交递增。
     */
    private long revision;

    /**
     * 会话协议消息。
     */
    private List<SessionMessage> messages = new ArrayList<>();

    /**
     * 面向前端的展示轮次，按时间升序，最多保留 20 轮。
     */
    private List<DisplayTurn> displayTurns = new ArrayList<>();

    /**
     * 本次读取是否失败（Redis 异常等）。
     * 失败时历史不可信，调用方不得用空历史覆盖原记录。
     */
    @JsonIgnore
    private boolean loadFailed;

    /**
     * 读取失败原因，仅日志与诊断使用。
     */
    @JsonIgnore
    private String loadError;
}


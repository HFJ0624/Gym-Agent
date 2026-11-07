package com.sau.gym.admin.agent.memory.model;

import com.sau.gym.model.vo.agent.AgentSourceVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 作者:hfj
 * 功能:面向前端的会话展示轮次
 * 说明:
 * 1. 只保存用户实际看到的文字与当时已校验的引用来源，
 *    不保存系统提示、摘要全文、tool_calls、工具原始结果或错误堆栈。
 * 2. turnId 使用该轮 runId，作为恢复合并与去重的稳定标识。
 * 3. sources 仅表示当时回答所使用的资料，不代表当前重新检索的结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisplayTurn implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 展示轮次标识（该轮 runId）。
     */
    private String turnId;

    /**
     * 运行标识，与 turnId 相同，冗余便于阅读。
     */
    private String runId;

    /**
     * 用户原始文字。
     */
    private String userText;

    /**
     * 最终客服回复文字。
     */
    private String replyText;

    /**
     * 本轮终态名称（AgentRunStatus.name()）。
     */
    private String status;

    /**
     * 当时回答实际引用的证据来源。
     */
    @Builder.Default
    private List<AgentSourceVO> sources = new ArrayList<>();

    /**
     * 创建时间（epoch 毫秒）。
     */
    private long createTime;
}

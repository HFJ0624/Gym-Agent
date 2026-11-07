package com.sau.gym.model.vo.agent;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 作者:hfj
 * 功能:会话快照中的展示轮次
 * 说明:
 * 只含用户实际看到的文字与当时已校验的引用；
 * sources 仅表示当时回答所用资料，不代表重新检索了当前规则。
 */
@Data
public class AgentTurnVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 展示轮次标识（该轮 runId），用于前端去重合并。
     */
    private String turnId;

    /**
     * 用户原始文字。
     */
    private String userText;

    /**
     * 最终客服回复。
     */
    private String replyText;

    /**
     * 该轮终态。
     */
    private String status;

    /**
     * 当时回答实际引用的来源。
     */
    private List<AgentSourceVO> sources;

    /**
     * 创建时间（epoch 毫秒）。
     */
    private Long createTime;
}

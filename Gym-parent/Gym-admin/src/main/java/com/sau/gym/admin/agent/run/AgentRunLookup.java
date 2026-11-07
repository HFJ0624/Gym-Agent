package com.sau.gym.admin.agent.run;

import lombok.Data;

/**
 * 作者:hfj
 * 功能:运行记录查询结果
 * 说明:
 * available=false 表示 Redis 暂时不可用，状态不可查询，
 * 调用方必须把它与“会话不存在”区分开，不能当成失败或无会话。
 */
@Data
public class AgentRunLookup {

    /**
     * 是否成功读取到状态存储。
     */
    private boolean available;

    /**
     * 读取到的运行记录；available=false 或会话无运行时为 null。
     */
    private AgentRunRecord record;

    public static AgentRunLookup of(AgentRunRecord record) {
        AgentRunLookup lookup = new AgentRunLookup();
        lookup.setAvailable(true);
        lookup.setRecord(record);
        return lookup;
    }

    public static AgentRunLookup unavailable() {
        AgentRunLookup lookup = new AgentRunLookup();
        lookup.setAvailable(false);
        lookup.setRecord(null);
        return lookup;
    }
}

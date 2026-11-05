package com.sau.gym.admin.agent.sop;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 作者:hfj
 * 功能:SOP 场景定义
 * 说明:
 * 来自服务端 resources/agent/sop/catalog.json 的可信配置。
 * allowedTools 是该场景建议的工具集合，实际执行权限仍与
 * 真实注册工具和当次授权求交集，加载 SOP 不能扩大权限。
 */
@Data
public class SopDefinition {

    /**
     * 场景名称。
     */
    private String name;

    /**
     * 场景描述。
     */
    private String description;

    /**
     * SOP 版本。
     */
    private String version;

    /**
     * 适用的意图枚举名。
     */
    private List<String> applicableIntents = new ArrayList<>();

    /**
     * 场景需要的必要参数槽位。
     */
    private List<String> requiredSlots = new ArrayList<>();

    /**
     * 建议工具集合。
     */
    private List<String> allowedTools = new ArrayList<>();

    /**
     * 正文资源路径。
     */
    private String bodyPath;
}

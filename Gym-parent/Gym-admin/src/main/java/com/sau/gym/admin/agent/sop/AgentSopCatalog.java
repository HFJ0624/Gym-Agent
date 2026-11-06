package com.sau.gym.admin.agent.sop;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 作者:hfj
 * 功能:客服 SOP 目录加载器
 * 说明:
 * 1. SOP 只来自可信服务端资源，不加载用户上传资料或 RAG 片段作为系统行为规则。
 * 2. 目录与正文为静态资源，启动时加载并缓存；运行中注入哪些 SOP 由本次上下文决定。
 * 3. 使用现有 Jackson 解析，不引入额外依赖。
 */
@Component
public class AgentSopCatalog {

    private static final Logger log = LoggerFactory.getLogger(AgentSopCatalog.class);

    /**
     * 目录资源路径。
     */
    private static final String CATALOG_PATH = "agent/sop/catalog.json";

    private final ObjectMapper objectMapper;

    /**
     * 目录版本。
     */
    private String catalogVersion;

    /**
     * 全部 SOP 定义。
     */
    private final List<SopDefinition> sops = new ArrayList<>();

    /**
     * SOP 正文缓存。
     */
    private final Map<String, String> bodyCache = new ConcurrentHashMap<>();

    public AgentSopCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 启动时加载目录。
     * 加载失败只记录告警，不阻断应用启动；SOP 缺失时按无 SOP 运行。
     */
    @PostConstruct
    public void init() {
        try (InputStream in = new ClassPathResource(CATALOG_PATH).getInputStream()) {
            Map<String, Object> catalog = objectMapper.readValue(in, Map.class);

            Object version = catalog.get("version");
            this.catalogVersion = version == null ? "unknown" : String.valueOf(version);

            Object sopList = catalog.get("sops");
            if (sopList instanceof List<?> list) {
                for (Object item : list) {
                    SopDefinition definition = objectMapper.convertValue(item, SopDefinition.class);
                    if (definition != null && StringUtils.hasText(definition.getName())) {
                        sops.add(definition);
                    }
                }
            }

            log.info("[AgentSop] 已加载 {} 个客服 SOP, catalogVersion={}", sops.size(), catalogVersion);
        } catch (Exception e) {
            log.warn("[AgentSop] SOP 目录加载失败，运行时按无 SOP 处理: {}", e.getMessage());
        }
    }

    /**
     * 按意图选择单个 SOP（兼容旧调用）。
     *
     * @param intentName      意图枚举名，可为空
     * @param hasPendingDraft 当前会话是否存在待确认草稿
     */
    public SopDefinition select(String intentName, boolean hasPendingDraft) {
        List<SopDefinition> selected = selectSops(intentName, null, null);
        return selected.isEmpty() ? null : selected.get(0);
    }

    /**
     * 组合场景选择：场景选择与工具选择统一到一次规划结果。
     * 命中规则（满足其一即入选）：
     * 1. 意图匹配该 SOP 的 applicableIntents。
     * 2. 当前会话待确认草稿类型对应该 SOP 的办理域。
     * 3. 用户输入包含该 SOP 的明确办理型 triggerPatterns（例如“帮我取消”“买两瓶”）。
     * 纯咨询措辞（例如“取消规则是什么”）不会命中办理型 SOP。
     *
     * @param intentName   主意图枚举名，可为空
     * @param message      用户原始输入，可为空
     * @param draftTypeName 当前待确认草稿类型名（BOOKING/SHOPPING/CANCEL_BOOKING），可为空
     * @return 选中的 SOP 列表（最多 3 个，保持目录顺序）
     */
    public List<SopDefinition> selectSops(String intentName, String message, String draftTypeName) {
        List<SopDefinition> selected = new ArrayList<>();

        if (sops.isEmpty()) {
            return selected;
        }

        for (SopDefinition definition : sops) {
            if (matchesIntent(definition, intentName)
                    || matchesDraft(definition, draftTypeName)
                    || matchesTrigger(definition, message)) {
                selected.add(definition);
            }
        }

        return selected.size() > 3 ? selected.subList(0, 3) : selected;
    }

    /**
     * 意图匹配。
     */
    private boolean matchesIntent(SopDefinition definition, String intentName) {
        return StringUtils.hasText(intentName)
                && definition.getApplicableIntents() != null
                && definition.getApplicableIntents().contains(intentName);
    }

    /**
     * 待确认草稿类型匹配：有待办时把真实草稿类型纳入场景判断。
     */
    private boolean matchesDraft(SopDefinition definition, String draftTypeName) {
        if (!StringUtils.hasText(draftTypeName)) {
            return false;
        }

        return switch (draftTypeName) {
            case "BOOKING" -> "booking-assist".equals(definition.getName());
            case "SHOPPING" -> "shopping-assist".equals(definition.getName());
            case "CANCEL_BOOKING" -> "cancel-assist".equals(definition.getName());
            default -> false;
        };
    }

    /**
     * 明确办理型措辞匹配。
     */
    private boolean matchesTrigger(SopDefinition definition, String message) {
        if (!StringUtils.hasText(message)
                || definition.getTriggerPatterns() == null
                || definition.getTriggerPatterns().isEmpty()) {
            return false;
        }

        return definition.getTriggerPatterns().stream().anyMatch(message::contains);
    }

    /**
     * 加载 SOP 正文（带缓存）。
     * 读取失败返回空串，不会让 SOP 内容阻断对话。
     */
    public String loadBody(SopDefinition definition) {
        if (definition == null || !StringUtils.hasText(definition.getBodyPath())) {
            return "";
        }

        return bodyCache.computeIfAbsent(definition.getBodyPath(), path -> {
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("[AgentSop] SOP 正文加载失败, path={}, reason={}", path, e.getMessage());
                return "";
            }
        });
    }

    /**
     * 目录版本。
     */
    public String getCatalogVersion() {
        return catalogVersion;
    }

    /**
     * 已加载的 SOP 数量。
     */
    public int size() {
        return sops.size();
    }
}

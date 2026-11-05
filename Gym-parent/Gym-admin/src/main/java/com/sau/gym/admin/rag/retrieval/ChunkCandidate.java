package com.sau.gym.admin.rag.retrieval;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:向量检索候选片段
 * 说明:
 * 使用纯 Java 字段描述一个候选，便于在过滤器与测试中脱离 LangChain4j 类型使用。
 */
@Data
public class ChunkCandidate {

    /**
     * 向量 metadata 中的 docId。
     */
    private Long docId;

    /**
     * 向量 metadata 中的 chunkIndex。
     */
    private Integer chunkIndex;

    /**
     * 片段正文。
     */
    private String text;

    /**
     * 向量相关性分数。
     */
    private Double score;

    /**
     * 原始 metadata 快照（全部为 String 值）。
     */
    private Map<String, String> metadata = new LinkedHashMap<>();
}

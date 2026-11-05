package com.sau.gym.admin.rag.config;


import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.sau.gym.admin.rag.embedding.VolcanoMultimodalEmbeddingModel;
/**
 * 作者:hfj
 * 功能:RAG相关Bean配置。
 * 日期: 2026/4/30 15:59
 */
@Configuration
public class RagConfig {

    /**
     * embedding base-url。
     *
     * 对应配置：
     * gym.rag.embedding.base-url
     */
    @Value("${gym.rag.embedding.base-url}")
    private String embeddingBaseUrl;

    /**
     * embedding API Key。
     *
     * 对应配置：
     * gym.rag.embedding.api-key
     */
    @Value("${gym.rag.embedding.api-key}")
    private String embeddingApiKey;

    /**
     * embedding 模型名称。
     *
     * 对应配置：
     * gym.rag.embedding.model-name
     */
    @Value("${gym.rag.embedding.model-name}")
    private String embeddingModelName;

    /**
     * pgvector 主机。
     */
    @Value("${gym.rag.pgvector.host}")
    private String pgHost;

    /**
     * pgvector 端口。
     */
    @Value("${gym.rag.pgvector.port}")
    private Integer pgPort;

    /**
     * pgvector 数据库名。
     */
    @Value("${gym.rag.pgvector.database}")
    private String pgDatabase;

    /**
     * pgvector 用户名。
     */
    @Value("${gym.rag.pgvector.user}")
    private String pgUser;

    /**
     * pgvector 密码。
     */
    @Value("${gym.rag.pgvector.password}")
    private String pgPassword;

    /**
     * pgvector 向量表名。
     */
    @Value("${gym.rag.pgvector.table}")
    private String embeddingTable;

    /**
     * 向量维度。
     *
     * 这个值必须和 embedding 模型输出维度一致。
     * 例如 doubao-embedding-large-text-250515 通常是 2048。
     */
    @Value("${gym.rag.pgvector.dimension}")
    private Integer embeddingDimension;

    /**
     * 说明：
     * RAG 原独立聊天模型 Bean（ragChatModel）已在第二轮移除。
     * 知识回答统一走第一轮自建 LlmClient（gym.ai.* 配置），
     * 避免同一回答出现两套模型客户端与两种超时策略。
     */

    /**
     * Embedding 模型。
     * 注意：
     * 当前火山方舟给你的 Doubao-embedding-vision 走的是：
     * /api/v3/embeddings/multimodal
     *
     * 不能再使用 LangChain4j 的 OpenAiEmbeddingModel，
     * 因为它默认调用 /api/v3/embeddings。
     */
    @Bean(name = "ragEmbeddingModel")
    public EmbeddingModel ragEmbeddingModel() {
        return new VolcanoMultimodalEmbeddingModel(
                embeddingBaseUrl,
                embeddingApiKey,
                embeddingModelName,
                embeddingDimension
        );
    }

    /**
     * PGVector 向量存储。
     *
     * 作用：
     * 1. 存储知识 chunk 的 embedding
     * 2. 根据用户问题 embedding 检索相似 chunk
     */
    @Bean
    public EmbeddingStore<TextSegment> ragEmbeddingStore() {
        return PgVectorEmbeddingStore.builder()
                // pgvector 主机
                .host(pgHost)
                // pgvector 端口
                .port(pgPort)
                // pgvector 数据库名
                .database(pgDatabase)
                // pgvector 用户名
                .user(pgUser)
                // pgvector 密码
                .password(pgPassword)
                // 向量表名
                .table(embeddingTable)
                // 向量维度必须和 embedding 模型一致
                .dimension(embeddingDimension)
                // 如果表不存在，自动创建
                .createTable(true)
                // 不要启动时删表，否则每次重启知识库都会丢
                .dropTableFirst(false)
                // 使用 JSONB 保存 metadata，例如 docId、title、venueId、topic
                //.metadataStorageConfig(MetadataStorageConfig.combinedJsonb()) 暂时不用
                .build();
    }
}

package com.sau.gym.admin.rag.service.impl;

import com.sau.gym.admin.mapper.KnowledgeDocumentMapper;
import com.sau.gym.admin.rag.chunk.SimpleTextChunker;
import com.sau.gym.admin.rag.retrieval.RagIndexMetadataFactory;
import com.sau.gym.admin.rag.service.RagKnowledgeIngestService;
import com.sau.gym.model.dto.rag.KnowledgeDocumentSaveDto;
import com.sau.gym.model.entity.rag.KnowledgeDocument;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 作者:hfj
 * 功能:RAG知识入库服务实现
 * 日期: 2026/4/30 16:30
 */
@Service
public class RagKnowledgeIngestServiceImpl implements RagKnowledgeIngestService {

    @Autowired
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Autowired
    private SimpleTextChunker simpleTextChunker;

    /**
     * RAG 专用 embedding 模型。
     * 用于知识入库时将 chunk 转为向量。
     */
    @Resource(name = "ragEmbeddingModel")
    private EmbeddingModel ragEmbeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> ragEmbeddingStore;

    @Value("${gym.rag.pgvector.table}")
    private String embeddingTable;

    @Value("${gym.rag.retrieval.chunk.size}")
    private Integer chunkSize;

    @Value("${gym.rag.retrieval.chunk.overlap}")
    private Integer chunkOverlap;

    /**
     * pgvector 主机地址。
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
     * 保存知识文档到 MySQL。
     *
     * 这里保存的只是原始知识。
     * 真正写入 pgvector，需要调用 rebuildAll()。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveDocument(KnowledgeDocumentSaveDto dto) {
        if (dto == null) {
            throw new RuntimeException("知识文档不能为空");
        }

        if (dto.getTitle() == null || dto.getTitle().trim().isEmpty()) {
            throw new RuntimeException("知识标题不能为空");
        }

        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            throw new RuntimeException("知识正文不能为空");
        }

        if (dto.getKnowledgeScope() == null) {
            throw new RuntimeException("知识范围不能为空");
        }

        if (dto.getSourceType() == null) {
            throw new RuntimeException("知识来源类型不能为空");
        }

        KnowledgeDocument document = new KnowledgeDocument();

        // 基础知识内容
        document.setTitle(dto.getTitle());
        document.setContent(dto.getContent());

        // 知识范围和来源类型
        document.setKnowledgeScope(dto.getKnowledgeScope());
        document.setSourceType(dto.getSourceType());

        // 场馆关联信息
        document.setVenueId(dto.getVenueId());
        document.setVenueName(dto.getVenueName());

        // 场地关联信息
        document.setCourtId(dto.getCourtId());
        document.setCourtName(dto.getCourtName());
        document.setCourtType(dto.getCourtType());

        // 公告关联信息
        document.setNoticeId(dto.getNoticeId());

        // 标签和主题
        document.setTopic(dto.getTopic());
        document.setTags(dto.getTags());

        // 优先级，未传时默认 0
        document.setPriority(dto.getPriority() == null ? 0 : dto.getPriority());
        knowledgeDocumentMapper.insert(document);
    }

    /**
     * 重建所有知识向量索引。
     *
     * 注意：
     * 第一版采用“全量重建”，逻辑最简单。
     * 后续再做增量更新、删除旧 chunk、按文档重建。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuildAll() {
        // 1. 清空 pgvector 表，避免重复入库导致同一知识被检索出多次
        truncateEmbeddingTable();

        // 2. 查询 MySQL 中所有启用知识
        List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectEnabledDocuments();

        // 3. 遍历每一篇知识文档
        for (KnowledgeDocument document : documents) {
            ingestSingleDocument(document);
        }
    }

    /**
     * 把一篇知识文档切分、向量化并写入 pgvector。
     *
     * @param document 知识文档
     */
    private void ingestSingleDocument(KnowledgeDocument document) {
        // 1. 把长文本切成多个 chunk
        List<String> chunks = simpleTextChunker.split(
                document.getContent(),
                chunkSize,
                chunkOverlap
        );

        // 2. 遍历 chunk，逐个生成 embedding 并写入向量库
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);

            // 3. 构造 metadata
            // 统一使用 RagIndexMetadataFactory，保证与增量重建入口的
            // 字段格式和内容指纹（contentHash）完全一致，
            // 检索阶段据此校验证据的内容版本。
            Map<String, Object> metadataMap =
                    RagIndexMetadataFactory.buildMetadata(document, i);

            Metadata metadata = Metadata.from(metadataMap);

            // 4. TextSegment = 文本片段 + metadata
            TextSegment segment = TextSegment.from(chunkText, metadata);

            // 5. 生成 embedding
            Embedding embedding = ragEmbeddingModel.embed(segment).content();

            // 6. 写入 pgvector
            ragEmbeddingStore.add(embedding, segment);
        }

        Date date = new Date();
        // 7. 标记该知识文档已完成索引
        knowledgeDocumentMapper.markIndexed(document.getId(),date);
    }

    /**
     * 清空 pgvector 向量表。
     *
     * 注意：
     * 这里不使用 Spring DataSource。
     * 原因是：如果把 PostgreSQL DataSource 注册成 Spring Bean，
     * Spring Boot 可能会把它当作默认数据源，导致 MyBatis 业务查询连错数据库。
     *
     * 所以这里只在需要清空向量表时，临时创建 PostgreSQL JDBC 连接。
     */
    private void truncateEmbeddingTable() {
        String jdbcUrl = "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDatabase;

        try (
                Connection connection = DriverManager.getConnection(jdbcUrl, pgUser, pgPassword);
                Statement statement = connection.createStatement()
        ) {
            statement.execute("TRUNCATE TABLE " + embeddingTable);
            System.out.println("[RAG] 已清空 pgvector 向量表：" + embeddingTable);
        } catch (Exception e) {
            // 第一次启动时，向量表可能还没有被 LangChain4j 创建。
            // 这里不能抛异常，否则会影响知识重建流程。
            System.out.println("[RAG] 清空 pgvector 表失败，可能是表暂不存在：" + e.getMessage());
        }
    }
}

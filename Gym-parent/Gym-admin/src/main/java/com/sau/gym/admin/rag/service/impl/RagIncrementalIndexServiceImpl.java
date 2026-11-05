package com.sau.gym.admin.rag.service.impl;

import com.sau.gym.admin.mapper.KnowledgeDocumentMapper;
import com.sau.gym.admin.rag.retrieval.RagIndexMetadataFactory;
import com.sau.gym.admin.rag.service.RagIncrementalIndexService;
import com.sau.gym.admin.rag.service.RagVectorCleanService;
import com.sau.gym.model.entity.rag.KnowledgeDocument;
import com.sau.gym.model.vo.rag.RagIndexResultVO;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 作者:hfj
 * 功能:RAG 单条知识增量索引实现
 * 核心流程：
 * 1. 查 knowledge_document
 * 2. 删除 pgvector 旧向量
 * 3. 构造文本
 * 4. 切分 chunk
 * 5. embedding
 * 6. 写入 pgvector
 * 7. 更新知识索引状态
 * 日期: 2026/5/6 14:44
 */
@Service
public class RagIncrementalIndexServiceImpl implements RagIncrementalIndexService {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final RagVectorCleanService ragVectorCleanService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public RagIncrementalIndexServiceImpl(KnowledgeDocumentMapper knowledgeDocumentMapper,
                                          RagVectorCleanService ragVectorCleanService,
                                          EmbeddingModel embeddingModel,
                                          EmbeddingStore<TextSegment> embeddingStore) {
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.ragVectorCleanService = ragVectorCleanService;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 单条知识重新索引。
     */
    @Override
    public RagIndexResultVO reindexOne(Long docId) {
        RagIndexResultVO result = new RagIndexResultVO();
        result.setDocId(docId);
        result.setSuccess(false);

        if (docId == null) {
            result.setMessage("知识ID不能为空");
            return result;
        }

        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(docId);

        if (doc == null) {
            result.setMessage("知识不存在，docId=" + docId);
            return result;
        }

        result.setTitle(doc.getTitle());

        try {

            //1. 删除旧向量。
            int deletedCount = ragVectorCleanService.deleteByDocId(docId);
            result.setDeletedCount(deletedCount);

            //2. 构造索引用文本。
            String fullText = buildIndexText(doc);

            if (!StringUtils.hasText(fullText)) {
                knowledgeDocumentMapper.updateIndexFail(docId, "知识内容为空，无法索引");
                result.setMessage("知识内容为空，无法索引");
                return result;
            }

            //3. 切分chunk。
            List<String> chunks = splitText(fullText, 500, 80);

            if (chunks.isEmpty()) {
                knowledgeDocumentMapper.updateIndexFail(docId, "知识切分后为空");
                result.setMessage("知识切分后为空");
                return result;
            }

            //4. 逐段生成embedding并写入pgvector。
            // 单条知识用逐段写入即可，稳定优先。如果后续数据量大，可以改成 batch embedding。
            int indexedCount = 0;

            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);

                Metadata metadata = buildMetadata(doc, i);

                TextSegment segment = TextSegment.from(chunkText, metadata);

                Embedding embedding = embeddingModel.embed(segment).content();

                embeddingStore.add(embedding, segment);

                indexedCount++;
            }

            //5. 更新 MySQL knowledge_document 索引状态。
            knowledgeDocumentMapper.updateIndexSuccess(docId, indexedCount);

            result.setIndexedChunkCount(indexedCount);
            result.setSuccess(true);
            result.setMessage("单条知识索引成功");

            return result;

        } catch (Exception e) {
            e.printStackTrace();

            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.length() > 1000) {
                errorMessage = errorMessage.substring(0, 1000);
            }

            knowledgeDocumentMapper.updateIndexFail(docId, errorMessage);

            result.setSuccess(false);
            result.setMessage("单条知识索引失败：" + errorMessage);

            return result;
        }
    }

    /**
     * 构造索引用文本。
     * 不只索引 content，也把 title、scope、场馆、场地信息一起拼进去。
     * 这样用户问“沈航篮球场多少钱”时更容易命中。
     */
    private String buildIndexText(KnowledgeDocument doc) {
        StringBuilder builder = new StringBuilder();

        appendLine(builder, "标题", doc.getTitle());
        appendLine(builder, "知识范围", String.valueOf(doc.getKnowledgeScope()));
        appendLine(builder, "主题", doc.getTopic());
        appendLine(builder, "场馆", doc.getVenueName());
        appendLine(builder, "场地", doc.getCourtName());
        appendLine(builder, "内容", doc.getContent());

        return builder.toString();
    }

    /**
     * 拼接一行文本。
     */
    private void appendLine(StringBuilder builder, String label, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(label).append("：").append(value).append("\n");
        }
    }

    /**
     * 构造 metadata。
     * docId 是必须字段，单条删除旧向量依赖 metadata.docId。
     * 统一委托 RagIndexMetadataFactory，保证与全量入库入口的
     * 字段格式、contentHash 内容指纹完全一致，
     * 避免普通入库与增量重建产生不同格式的元数据。
     */
    private Metadata buildMetadata(KnowledgeDocument doc, int chunkIndex) {
        return Metadata.from(RagIndexMetadataFactory.buildMetadata(doc, chunkIndex));
    }

    /***
     *
     * @param text 文本
     * @param chunkSize 每段最大长度
     * @param overlap 相邻chunk重叠长度
     * @return 简单文本切分
     */
    private List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();

        if (!StringUtils.hasText(text)) {
            return chunks;
        }

        String normalized = text.trim();

        if (normalized.length() <= chunkSize) {
            chunks.add(normalized);
            return chunks;
        }

        int start = 0;

        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());

            String chunk = normalized.substring(start, end).trim();

            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }

            if (end >= normalized.length()) {
                break;
            }

            start = Math.max(end - overlap, start + 1);
        }

        return chunks;
    }
}

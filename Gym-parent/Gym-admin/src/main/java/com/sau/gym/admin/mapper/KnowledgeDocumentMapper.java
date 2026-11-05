package com.sau.gym.admin.mapper;

import com.sau.gym.model.dto.rag.KnowledgeDocumentQueryDto;
import com.sau.gym.model.entity.rag.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface KnowledgeDocumentMapper {

    /**
     * 新增知识文档。
     * @param document 知识文档
     * @return 影响行数
     */
    int insert(KnowledgeDocument document);

    /**
     * 查询所有启用的知识文档。
     * 这些文档会被切分并写入 pgvector。
     * @return 启用的知识文档列表
     */
    List<KnowledgeDocument> selectEnabledDocuments();

    /**
     * 标记知识文档已完成向量索引。
     * @param id 知识文档ID
     * @return 影响行数
     */
    int markIndexed(@Param("id") Long id,@Param("date") Date date);

    /**
     * 根据条件分页查询知识文档。
     */
    List<KnowledgeDocument> selectPage(KnowledgeDocumentQueryDto queryDto);

    /**
     * 根据ID查询知识文档。
     */
    KnowledgeDocument selectById(@Param("id") Long id);

    /**
     * 根据 ID 批量查询知识文档。
     * 供检索证据有效性校验使用，一次查询所有命中文档，避免每个 chunk 查一次库。
     */
    List<KnowledgeDocument> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 更新知识文档。
     *
     * 注意：
     * 只要知识内容发生变化，就把 indexed_status 改回 0。
     * 因为 pgvector 里的旧向量已经不是最新内容。
     */
    int updateById(KnowledgeDocument document);

    /**
     * 修改启用状态。
     */
    int updateEnabled(@Param("id") Long id, @Param("enabled") Integer enabled,@Param("date")Date date);

    /***
     *
     * @param id 文档id
     * @return 删除知识文档
     */
    int deleteById(@Param("id") Long id);

    /**
     * 重置全部索引状态。
     *
     * 全量 rebuild 前可以先把 indexed_status 改成 0。
     */
    int resetAllIndexedStatus(Date date);

    /**
     * 根据场馆ID和来源类型查询知识文档。
     * 用于判断某个场馆的 RAG 知识是否已经存在。
     */
    KnowledgeDocument selectByVenueIdAndSourceType(@Param("venueId") Long venueId, @Param("sourceType") Integer sourceType);

    /**
     * 根据场馆ID和来源类型更新知识文档。
     * 用于同步场馆知识时覆盖旧内容。
     */
    int updateByVenueIdAndSourceType(KnowledgeDocument document);

    /**
     * 根据场地ID和来源类型查询知识文档。
     * 用于判断某个场地的 RAG 知识是否已经存在。
     */
    KnowledgeDocument selectByCourtIdAndSourceType(@Param("courtId") Long courtId, @Param("sourceType") Integer sourceType);

    /**
     * 根据场地ID和来源类型更新知识文档。
     * 用于同步场地知识时覆盖旧内容。
     */
    int updateByCourtIdAndSourceType(KnowledgeDocument document);

    /**
     * 更新索引成功状态。
     */
    int updateIndexSuccess(@Param("id") Long id, @Param("chunkCount") Integer chunkCount);

    /**
     * 更新索引失败状态。
     */
    int updateIndexFail(@Param("id") Long id, @Param("errorMessage") String errorMessage);
}

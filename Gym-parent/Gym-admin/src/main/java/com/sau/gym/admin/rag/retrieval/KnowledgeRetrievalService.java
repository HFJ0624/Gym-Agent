package com.sau.gym.admin.rag.retrieval;

/**
 * 作者:hfj
 * 功能:知识检索服务接口
 * 说明:
 * 只负责 Embedding、向量检索、范围/有效性过滤、去重和证据组装。
 * 不调用任何生成式 Chat 模型，不调用 RagQaService.ask()，不递归调用 Agent。
 */
public interface KnowledgeRetrievalService {

    /**
     * 执行一次纯检索。
     *
     * @param request 检索请求
     * @return 检索结果，包含状态、证据列表与过程信息
     */
    KnowledgeRetrievalResult retrieve(KnowledgeRetrievalRequest request);
}

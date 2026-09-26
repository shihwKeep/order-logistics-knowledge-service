package com.xjjk.knowledge.retrieval.model;

/** 当前检索范围内唯一确定一个文档版本的三元组。 */
public record DocumentVersionRef(long knowledgeBaseId, long documentId, long versionId) {
    public DocumentVersionRef {
        if (knowledgeBaseId <= 0 || documentId <= 0 || versionId <= 0) {
            throw new IllegalArgumentException("知识库、文档和版本ID必须为正数");
        }
    }

    public String key() {
        return knowledgeBaseId + ":" + documentId + ":" + versionId;
    }
}

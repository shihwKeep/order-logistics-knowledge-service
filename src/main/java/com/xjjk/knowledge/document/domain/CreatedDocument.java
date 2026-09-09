package com.xjjk.knowledge.document.domain;

/** 同一事务中创建的文档和首个草稿版本。 */
public record CreatedDocument(KnowledgeDocument document, DocumentVersion version) {
}

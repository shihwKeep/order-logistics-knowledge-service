package com.xjjk.knowledge.document.domain;

import java.time.LocalDateTime;

/** 文档逻辑身份；草稿和发布版本通过两个独立指针管理。 */
public record KnowledgeDocument(
        long id,
        long tenantId,
        long knowledgeBaseId,
        String title,
        Long currentDraftVersionId,
        Long currentPublishedVersionId,
        long createdBy,
        long updatedBy,
        int rowVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

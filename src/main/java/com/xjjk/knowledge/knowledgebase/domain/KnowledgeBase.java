package com.xjjk.knowledge.knowledgebase.domain;

import java.time.OffsetDateTime;

/** 知识库聚合根。 */
public record KnowledgeBase(
        long id,
        long tenantId,
        String name,
        String description,
        KnowledgeBaseStatus status,
        int rowVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

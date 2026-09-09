package com.xjjk.knowledge.knowledgebase.web.dto;

import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBase;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBaseStatus;

import java.time.OffsetDateTime;

/** 管理端知识库响应。 */
public record KnowledgeBaseResponse(
        long id,
        long tenantId,
        String name,
        String description,
        KnowledgeBaseStatus status,
        int rowVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static KnowledgeBaseResponse from(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(
                knowledgeBase.id(),
                knowledgeBase.tenantId(),
                knowledgeBase.name(),
                knowledgeBase.description(),
                knowledgeBase.status(),
                knowledgeBase.rowVersion(),
                knowledgeBase.createdAt(),
                knowledgeBase.updatedAt());
    }
}

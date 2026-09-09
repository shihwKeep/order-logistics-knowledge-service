package com.xjjk.knowledge.document.web.dto;

import com.xjjk.knowledge.document.domain.CreatedDocument;

import java.time.LocalDateTime;

public record DocumentResponse(
        long id,
        long tenantId,
        long knowledgeBaseId,
        String title,
        DocumentVersionResponse currentDraftVersion,
        Long currentPublishedVersionId,
        int rowVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static DocumentResponse from(CreatedDocument created) {
        return new DocumentResponse(
                created.document().id(),
                created.document().tenantId(),
                created.document().knowledgeBaseId(),
                created.document().title(),
                DocumentVersionResponse.from(created.version()),
                created.document().currentPublishedVersionId(),
                created.document().rowVersion(),
                created.document().createdAt(),
                created.document().updatedAt());
    }
}

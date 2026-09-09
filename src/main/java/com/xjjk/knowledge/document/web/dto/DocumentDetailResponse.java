package com.xjjk.knowledge.document.web.dto;

import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import java.time.LocalDateTime;
import java.util.List;

public record DocumentDetailResponse(
        long id,
        long tenantId,
        long knowledgeBaseId,
        String title,
        Long currentDraftVersionId,
        Long currentPublishedVersionId,
        int rowVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<DocumentVersionResponse> versions) {
    public static DocumentDetailResponse from(KnowledgeDocument document, List<DocumentVersion> versions) {
        return new DocumentDetailResponse(
                document.id(), document.tenantId(), document.knowledgeBaseId(), document.title(),
                document.currentDraftVersionId(), document.currentPublishedVersionId(), document.rowVersion(),
                document.createdAt(), document.updatedAt(),
                versions.stream().map(DocumentVersionResponse::from).toList());
    }
}

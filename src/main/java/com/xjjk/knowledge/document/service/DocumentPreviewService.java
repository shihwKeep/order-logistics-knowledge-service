package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.storage.SourceObjectStore;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class DocumentPreviewService {
    private static final Set<String> INLINE_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp", "text/plain");

    private final DocumentQueryService queries;
    private final SourceObjectStore objects;

    public DocumentPreviewService(DocumentQueryService queries, SourceObjectStore objects) {
        this.queries = queries;
        this.objects = objects;
    }

    public DocumentPreview source(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        DocumentVersion version = queries.requireVersion(
                principal, tenantId, knowledgeBaseId, documentId, versionId);
        String contentType = normalizeContentType(version.mimeType());
        return new DocumentPreview(
                objects.get(version.sourceObjectKey()), version.originalFilename(), contentType,
                version.fileSize(), INLINE_TYPES.contains(contentType));
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank() || contentType.contains("\r") || contentType.contains("\n")) {
            return "application/octet-stream";
        }
        int parameters = contentType.indexOf(';');
        return (parameters < 0 ? contentType : contentType.substring(0, parameters)).trim().toLowerCase();
    }
}

package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.persistence.DocumentRepository;
import com.xjjk.knowledge.document.task.IngestionTask;
import com.xjjk.knowledge.knowledgebase.service.KnowledgeBaseService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DocumentQueryService {
    private final KnowledgeBaseService knowledgeBases;
    private final DocumentRepository documents;
    private final DocumentManagementRepository management;

    public DocumentQueryService(
            KnowledgeBaseService knowledgeBases,
            DocumentRepository documents,
            DocumentManagementRepository management) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.management = management;
    }

    public List<KnowledgeDocument> list(AdminPrincipal principal, long tenantId, long knowledgeBaseId) {
        knowledgeBases.get(principal, tenantId, knowledgeBaseId);
        return documents.listDocuments(tenantId, knowledgeBaseId);
    }

    public KnowledgeDocument document(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId) {
        knowledgeBases.get(principal, tenantId, knowledgeBaseId);
        KnowledgeDocument document = documents.findDocument(tenantId, documentId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.DOCUMENT_NOT_FOUND));
        if (document.knowledgeBaseId() != knowledgeBaseId) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    public List<DocumentVersion> versions(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId) {
        document(principal, tenantId, knowledgeBaseId, documentId);
        return documents.listVersions(tenantId, documentId);
    }

    public List<DocumentUnit> units(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId,
            Boolean lowConfidence, int offset, int limit) {
        requireVersion(principal, tenantId, knowledgeBaseId, documentId, versionId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return management.listUnits(tenantId, documentId, versionId, lowConfidence, safeOffset, safeLimit);
    }

    public List<DocumentChunkView> chunks(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId,
            int offset, int limit) {
        requireVersion(principal, tenantId, knowledgeBaseId, documentId, versionId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return management.listChunks(tenantId, documentId, versionId, safeOffset, safeLimit);
    }

    public Optional<IngestionTask> latestTask(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        requireVersion(principal, tenantId, knowledgeBaseId, documentId, versionId);
        return management.latestTask(tenantId, versionId);
    }

    public DocumentVersion requireVersion(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        document(principal, tenantId, knowledgeBaseId, documentId);
        DocumentVersion version = documents.findVersion(tenantId, documentId, versionId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.DOCUMENT_NOT_FOUND));
        if (version.knowledgeBaseId() != knowledgeBaseId) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_NOT_FOUND);
        }
        return version;
    }
}

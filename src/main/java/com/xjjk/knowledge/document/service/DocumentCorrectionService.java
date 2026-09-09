package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.audit.AuditAction;
import com.xjjk.knowledge.audit.AuditService;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.persistence.DocumentRepository;
import com.xjjk.knowledge.knowledgebase.service.KnowledgeBaseService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DocumentCorrectionService {
    private final KnowledgeBaseService knowledgeBases;
    private final DocumentRepository documents;
    private final DocumentManagementRepository management;
    private final AuditService audit;

    public DocumentCorrectionService(
            KnowledgeBaseService knowledgeBases,
            DocumentRepository documents,
            DocumentManagementRepository management,
            AuditService audit) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.management = management;
        this.audit = audit;
    }

    public DocumentUnit correct(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId,
            long unitId, String correctedText, String requestId) {
        requireVersion(principal, tenantId, knowledgeBaseId, documentId, versionId);
        String normalized = correctedText == null ? "" : correctedText.strip();
        if (normalized.isEmpty() || normalized.length() > 1_000_000) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
        DocumentUnit corrected = management.correctUnit(
                tenantId, knowledgeBaseId, documentId, versionId, unitId,
                normalized, principal.userId(), requestId);
        audit.success(
                tenantId, principal, AuditAction.DOCUMENT_UNIT_CORRECT,
                "DOCUMENT_UNIT", Long.toString(unitId), requestId, Map.of("versionId", versionId));
        return corrected;
    }

    public boolean retry(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId,
            String requestId) {
        requireVersion(principal, tenantId, knowledgeBaseId, documentId, versionId);
        if (!management.retry(tenantId, versionId)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED, "当前任务不允许重试");
        }
        audit.success(
                tenantId, principal, AuditAction.DOCUMENT_RETRY,
                "DOCUMENT_VERSION", Long.toString(versionId), requestId, Map.of("versionId", versionId));
        return true;
    }

    private void requireDocument(AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId) {
        knowledgeBases.get(principal, tenantId, knowledgeBaseId);
        KnowledgeDocument document = documents.findDocument(tenantId, documentId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.DOCUMENT_NOT_FOUND));
        if (document.knowledgeBaseId() != knowledgeBaseId) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_NOT_FOUND);
        }
    }

    private void requireVersion(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        requireDocument(principal, tenantId, knowledgeBaseId, documentId);
        DocumentVersion version = documents.findVersion(tenantId, documentId, versionId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.DOCUMENT_NOT_FOUND));
        if (version.knowledgeBaseId() != knowledgeBaseId) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_NOT_FOUND);
        }
    }
}

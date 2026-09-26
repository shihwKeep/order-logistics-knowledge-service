package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.audit.AuditAction;
import com.xjjk.knowledge.audit.AuditService;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.SourceFile;
import com.xjjk.knowledge.document.persistence.DocumentRepository;
import com.xjjk.knowledge.document.storage.SourceObjectStore;
import com.xjjk.knowledge.document.storage.UploadPolicy;
import com.xjjk.knowledge.document.storage.ValidatedUpload;
import com.xjjk.knowledge.knowledgebase.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.Map;

/** 文档上传编排：授权、校验、登记、原件存储和审计在一个明确入口完成。 */
@Service
public class DocumentUploadService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentRepository repository;
    private final SourceObjectStore objectStore;
    private final UploadPolicy uploadPolicy;
    private final AuditService auditService;

    public DocumentUploadService(
            KnowledgeBaseService knowledgeBaseService,
            DocumentRepository repository,
            SourceObjectStore objectStore,
            UploadPolicy uploadPolicy,
            AuditService auditService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.repository = repository;
        this.objectStore = objectStore;
        this.uploadPolicy = uploadPolicy;
        this.auditService = auditService;
    }

    /**
     * 数据库登记和 MinIO 写入共享外层事务：存储失败会回滚文档、版本、任务和 Outbox。
     * 极少数“对象写入成功但数据库提交失败”的孤儿对象由后续生命周期扫描清理。
     */
    @Transactional
    public CreatedDocument uploadNewDocument(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            String requestedTitle,
            String originalFilename,
            String declaredMimeType,
            byte[] content,
            String requestId) {
        knowledgeBaseService.get(principal, tenantId, knowledgeBaseId);
        ValidatedUpload validated = uploadPolicy.validate(originalFilename, declaredMimeType, content);
        String title = normalizeTitle(requestedTitle, validated.originalFilename());
        SourceFile source = new SourceFile(
                validated.originalFilename(),
                validated.extension(),
                validated.mimeType(),
                validated.size(),
                validated.sha256());

        var duplicate = repository.findByUploadRequest(tenantId, requestId);
        if (duplicate.isPresent()) {
            return requireMatchingDuplicate(
                    duplicate.get(), knowledgeBaseId, null, title, source);
        }

        CreatedDocument created = repository.createDocument(
                tenantId, knowledgeBaseId, principal.userId(), title, source, requestId);
        objectStore.put(
                created.version().sourceObjectKey(),
                new ByteArrayInputStream(content),
                content.length,
                validated.mimeType());
        auditService.success(
                tenantId,
                principal,
                AuditAction.DOCUMENT_UPLOAD,
                "DOCUMENT",
                Long.toString(created.document().id()),
                requestId,
                Map.of("documentTitle", title, "versionId", created.version().id()));
        return created;
    }

    @Transactional
    public CreatedDocument uploadNewVersion(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            String originalFilename,
            String declaredMimeType,
            byte[] content,
            String requestId) {
        knowledgeBaseService.get(principal, tenantId, knowledgeBaseId);
        ValidatedUpload validated = uploadPolicy.validate(originalFilename, declaredMimeType, content);
        SourceFile source = new SourceFile(
                validated.originalFilename(), validated.extension(), validated.mimeType(),
                validated.size(), validated.sha256());

        var duplicate = repository.findByUploadRequest(tenantId, requestId);
        if (duplicate.isPresent()) {
            return requireMatchingDuplicate(
                    duplicate.get(), knowledgeBaseId, documentId, null, source);
        }

        CreatedDocument created = repository.createVersion(
                tenantId, knowledgeBaseId, documentId, principal.userId(), source, requestId);
        objectStore.put(
                created.version().sourceObjectKey(),
                new ByteArrayInputStream(content),
                content.length,
                validated.mimeType());
        auditService.success(
                tenantId,
                principal,
                AuditAction.DOCUMENT_UPLOAD,
                "DOCUMENT_VERSION",
                Long.toString(created.version().id()),
                requestId,
                Map.of("documentId", documentId, "versionId", created.version().id()));
        return created;
    }

    /** 保留旧方法作为内部兼容入口，新代码应明确调用 uploadNewDocument。 */
    public CreatedDocument upload(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            String requestedTitle,
            String originalFilename,
            String declaredMimeType,
            byte[] content,
            String requestId) {
        return uploadNewDocument(
                principal, tenantId, knowledgeBaseId, requestedTitle,
                originalFilename, declaredMimeType, content, requestId);
    }

    private CreatedDocument requireMatchingDuplicate(
            CreatedDocument duplicate,
            long knowledgeBaseId,
            Long documentId,
            String title,
            SourceFile source) {
        boolean sameTarget = duplicate.document().knowledgeBaseId() == knowledgeBaseId
                && (documentId == null
                    ? title.equals(duplicate.document().title())
                    : documentId == duplicate.document().id())
                && source.sha256().equals(duplicate.version().sourceSha256());
        if (!sameTarget) {
            throw new BusinessException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return duplicate;
    }

    private String normalizeTitle(String requestedTitle, String filename) {
        String title = requestedTitle == null || requestedTitle.isBlank()
                ? withoutExtension(filename)
                : requestedTitle.trim();
        if (title.isBlank() || title.length() > 255) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
        return title;
    }

    private String withoutExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }
}

package com.xjjk.knowledge.document.persistence;

import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.domain.SourceFile;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MybatisDocumentRepository implements DocumentRepository {

    private final DocumentMapper mapper;

    public MybatisDocumentRepository(DocumentMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 先取得数据库主键，再生成不含用户文件名的稳定对象键；整个方法由事务保护，
     * 因而不会暴露只有文档没有版本的半成品记录。
     */
    @Override
    @Transactional
    public CreatedDocument createDocument(
            long tenantId,
            long knowledgeBaseId,
            long actorUserId,
            String title,
            SourceFile source,
            String requestId) {
        CreatedDocument duplicate = duplicateRequest(
                tenantId, knowledgeBaseId, null, title, source, requestId);
        if (duplicate != null) {
            return duplicate;
        }
        DocumentEntity document = new DocumentEntity();
        document.setTenantId(tenantId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setTitle(title);
        document.setCreatedBy(actorUserId);
        document.setUpdatedBy(actorUserId);
        mapper.insertDocument(document);

        DocumentVersionEntity version = new DocumentVersionEntity();
        version.setTenantId(tenantId);
        version.setKnowledgeBaseId(knowledgeBaseId);
        version.setDocumentId(document.getId());
        version.setVersionNumber(1);
        version.setStatus(DocumentStatus.UPLOADED.name());
        version.setOriginalFilename(source.originalFilename());
        version.setFileExtension(source.extension());
        version.setMimeType(source.mimeType());
        version.setFileSize(source.size());
        version.setSourceSha256(source.sha256());
        version.setSourceObjectKey("pending");
        version.setCreatedBy(actorUserId);
        version.setUploadRequestId(requestId);
        try {
            mapper.insertVersion(version);
        } catch (DuplicateKeyException duplicateKey) {
            mapper.deleteEmptyDocument(tenantId, document.getId());
            CreatedDocument concurrent = duplicateRequest(
                    tenantId, knowledgeBaseId, null, title, source, requestId);
            if (concurrent != null) {
                return concurrent;
            }
            throw duplicateKey;
        }

        String objectKey = sourceObjectKey(
                tenantId, knowledgeBaseId, document.getId(), version.getId());
        mapper.updateSourceObjectKey(
                tenantId, document.getId(), version.getId(), objectKey);
        enqueue(tenantId, knowledgeBaseId, document.getId(), version.getId());

        return new CreatedDocument(
                requireDocument(tenantId, document.getId()),
                requireVersion(tenantId, document.getId(), version.getId()));
    }

    @Override
    @Transactional
    public CreatedDocument createVersion(
            long tenantId,
            long knowledgeBaseId,
            long documentId,
            long actorUserId,
            SourceFile source,
            String requestId) {
        CreatedDocument duplicate = duplicateRequest(
                tenantId, knowledgeBaseId, documentId, null, source, requestId);
        if (duplicate != null) {
            return duplicate;
        }
        DocumentEntity document = mapper.lockDocument(tenantId, knowledgeBaseId, documentId);
        if (document == null) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_NOT_FOUND);
        }
        duplicate = duplicateRequest(tenantId, knowledgeBaseId, documentId, null, source, requestId);
        if (duplicate != null) {
            return duplicate;
        }
        if (mapper.findVersionByContent(tenantId, documentId, source.sha256()) != null) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_CONTENT_UNCHANGED);
        }

        DocumentVersionEntity version = newVersion(
                tenantId, knowledgeBaseId, documentId, mapper.nextVersionNumber(tenantId, documentId),
                actorUserId, source, requestId);
        try {
            mapper.insertVersion(version);
        } catch (DuplicateKeyException duplicateKey) {
            CreatedDocument concurrent = duplicateRequest(
                    tenantId, knowledgeBaseId, documentId, null, source, requestId);
            if (concurrent != null) {
                return concurrent;
            }
            throw duplicateKey;
        }
        String objectKey = sourceObjectKey(tenantId, knowledgeBaseId, documentId, version.getId());
        mapper.updateSourceObjectKey(tenantId, documentId, version.getId(), objectKey);
        enqueue(tenantId, knowledgeBaseId, documentId, version.getId());
        return new CreatedDocument(
                requireDocument(tenantId, documentId),
                requireVersion(tenantId, documentId, version.getId()));
    }

    private DocumentVersionEntity newVersion(
            long tenantId, long knowledgeBaseId, long documentId, int versionNumber,
            long actorUserId, SourceFile source, String requestId) {
        DocumentVersionEntity version = new DocumentVersionEntity();
        version.setTenantId(tenantId);
        version.setKnowledgeBaseId(knowledgeBaseId);
        version.setDocumentId(documentId);
        version.setVersionNumber(versionNumber);
        version.setStatus(DocumentStatus.UPLOADED.name());
        version.setOriginalFilename(source.originalFilename());
        version.setFileExtension(source.extension());
        version.setMimeType(source.mimeType());
        version.setFileSize(source.size());
        version.setSourceSha256(source.sha256());
        version.setUploadRequestId(requestId);
        version.setSourceObjectKey("pending");
        version.setCreatedBy(actorUserId);
        return version;
    }

    private CreatedDocument duplicateRequest(
            long tenantId, long knowledgeBaseId, Long documentId, String title,
            SourceFile source, String requestId) {
        DocumentVersionEntity existing = mapper.findVersionByUploadRequest(tenantId, requestId);
        if (existing == null) {
            return null;
        }
        DocumentEntity document = mapper.findDocument(tenantId, existing.getDocumentId());
        boolean sameTarget = document != null
                && existing.getKnowledgeBaseId() == knowledgeBaseId
                && (documentId == null ? title.equals(document.getTitle()) : documentId.equals(existing.getDocumentId()))
                && source.sha256().equals(existing.getSourceSha256());
        if (!sameTarget) {
            throw new BusinessException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return new CreatedDocument(toDomain(document), toDomain(existing));
    }

    private void enqueue(long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        mapper.insertInitialTask(
                tenantId, knowledgeBaseId, documentId, versionId,
                "PARSE:" + tenantId + ":" + versionId);
        mapper.insertInitialOutbox(
                UUID.randomUUID().toString(), tenantId, Long.toString(versionId),
                "{\"tenantId\":" + tenantId
                        + ",\"documentId\":" + documentId
                        + ",\"versionId\":" + versionId + "}");
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(long tenantId, long documentId) {
        return Optional.ofNullable(mapper.findDocument(tenantId, documentId)).map(this::toDomain);
    }

    @Override
    public Optional<CreatedDocument> findByUploadRequest(long tenantId, String requestId) {
        DocumentVersionEntity version = mapper.findVersionByUploadRequest(tenantId, requestId);
        if (version == null) {
            return Optional.empty();
        }
        DocumentEntity document = mapper.findDocument(tenantId, version.getDocumentId());
        return document == null
                ? Optional.empty()
                : Optional.of(new CreatedDocument(toDomain(document), toDomain(version)));
    }

    @Override
    public Optional<DocumentVersion> findVersion(long tenantId, long documentId, long versionId) {
        return Optional.ofNullable(mapper.findVersion(tenantId, documentId, versionId))
                .map(this::toDomain);
    }

    @Override
    public List<KnowledgeDocument> listDocuments(long tenantId, long knowledgeBaseId) {
        return mapper.listDocuments(tenantId, knowledgeBaseId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<DocumentVersion> listVersions(long tenantId, long documentId) {
        return mapper.listVersions(tenantId, documentId).stream().map(this::toDomain).toList();
    }

    private KnowledgeDocument requireDocument(long tenantId, long documentId) {
        return findDocument(tenantId, documentId).orElseThrow();
    }

    private DocumentVersion requireVersion(long tenantId, long documentId, long versionId) {
        return findVersion(tenantId, documentId, versionId).orElseThrow();
    }

    private String sourceObjectKey(
            long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        return "tenant/" + tenantId
                + "/knowledge-base/" + knowledgeBaseId
                + "/document/" + documentId
                + "/version/" + versionId
                + "/source";
    }

    private KnowledgeDocument toDomain(DocumentEntity entity) {
        return new KnowledgeDocument(
                entity.getId(),
                entity.getTenantId(),
                entity.getKnowledgeBaseId(),
                entity.getTitle(),
                entity.getCurrentDraftVersionId(),
                entity.getCurrentPublishedVersionId(),
                entity.getCreatedBy(),
                entity.getUpdatedBy(),
                entity.getRowVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private DocumentVersion toDomain(DocumentVersionEntity entity) {
        return new DocumentVersion(
                entity.getId(),
                entity.getTenantId(),
                entity.getKnowledgeBaseId(),
                entity.getDocumentId(),
                entity.getVersionNumber(),
                DocumentStatus.valueOf(entity.getStatus()),
                entity.getOriginalFilename(),
                entity.getFileExtension(),
                entity.getMimeType(),
                entity.getFileSize(),
                entity.getSourceSha256(),
                entity.getSourceObjectKey(),
                entity.getParsedObjectKey(),
                entity.getParserVersion(),
                entity.getChunkStrategyVersion(),
                entity.getEmbeddingModel(),
                entity.getEmbeddingDimension(),
                entity.getEmbeddingInstructionVersion(),
                entity.getIndexManifestSha256(),
                entity.getIndexedAt(),
                Boolean.TRUE.equals(entity.getOcrRequired()),
                entity.getCorrectionRevision(),
                entity.getUnitCount(),
                entity.getChunkCount(),
                entity.getFailureStage(),
                entity.getLastErrorCode(),
                entity.getLastErrorMessage(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}

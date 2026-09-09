package com.xjjk.knowledge.document.persistence;

import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.domain.SourceFile;
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
    public CreatedDocument createDraft(
            long tenantId,
            long knowledgeBaseId,
            long actorUserId,
            String title,
            SourceFile source) {
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
        mapper.insertVersion(version);

        String objectKey = sourceObjectKey(
                tenantId, knowledgeBaseId, document.getId(), version.getId());
        mapper.updateSourceObjectKey(
                tenantId, document.getId(), version.getId(), objectKey);
        mapper.updateDraftPointer(
                tenantId, document.getId(), version.getId(), actorUserId);
        mapper.insertInitialTask(
                tenantId,
                knowledgeBaseId,
                document.getId(),
                version.getId(),
                "PARSE:" + tenantId + ":" + version.getId());
        mapper.insertInitialOutbox(
                UUID.randomUUID().toString(),
                tenantId,
                Long.toString(version.getId()),
                "{\"tenantId\":" + tenantId
                        + ",\"documentId\":" + document.getId()
                        + ",\"versionId\":" + version.getId() + "}");

        return new CreatedDocument(
                requireDocument(tenantId, document.getId()),
                requireVersion(tenantId, document.getId(), version.getId()));
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(long tenantId, long documentId) {
        return Optional.ofNullable(mapper.findDocument(tenantId, documentId)).map(this::toDomain);
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

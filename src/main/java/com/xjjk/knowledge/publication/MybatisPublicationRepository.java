package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.persistence.DocumentEntity;
import com.xjjk.knowledge.document.persistence.DocumentVersionEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

@Repository
public class MybatisPublicationRepository implements PublicationRepository {
    private static final Set<DocumentStatus> ROLLBACKABLE =
            EnumSet.of(DocumentStatus.READY, DocumentStatus.PUBLISHED, DocumentStatus.ARCHIVED);

    private final PublicationMapper mapper;

    public MybatisPublicationRepository(PublicationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PublicationRecord> findByRequest(long tenantId, String requestId) {
        return Optional.ofNullable(mapper.findRecord(tenantId, requestId)).map(this::toRecord);
    }

    @Override
    public PublicationTarget loadVersionTarget(long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        DocumentEntity document = mapper.findDocument(tenantId, knowledgeBaseId, documentId);
        DocumentVersionEntity version = mapper.findVersion(tenantId, knowledgeBaseId, documentId, versionId);
        return target(document, version);
    }

    @Override
    public PublicationTarget loadCurrentPublishedTarget(long tenantId, long knowledgeBaseId, long documentId) {
        DocumentEntity document = mapper.findDocument(tenantId, knowledgeBaseId, documentId);
        if (document == null || document.getCurrentPublishedVersionId() == null) {
            throw new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT, "当前文档没有已发布版本");
        }
        DocumentVersionEntity version = mapper.findVersion(
                tenantId, knowledgeBaseId, documentId, document.getCurrentPublishedVersionId());
        return target(document, version);
    }

    @Override
    @Transactional
    public PublicationRecord activate(
            PublicationTarget expected, PublicationAction action, long actorUserId, String requestId) {
        Optional<PublicationRecord> duplicate = findByRequest(expected.document().tenantId(), requestId);
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        KnowledgeDocument snapshot = expected.document();
        DocumentEntity lockedDocument = mapper.lockDocument(
                snapshot.tenantId(), snapshot.knowledgeBaseId(), snapshot.id());
        DocumentVersionEntity lockedVersion = mapper.lockVersion(
                snapshot.tenantId(), snapshot.knowledgeBaseId(), snapshot.id(), expected.version().id());
        if (lockedDocument == null || lockedVersion == null
                || lockedDocument.getRowVersion() != snapshot.rowVersion()
                || lockedVersion.getCorrectionRevision() != expected.version().correctionRevision()
                || !Objects.equals(lockedVersion.getIndexManifestSha256(), expected.version().indexManifestSha256())) {
            throw new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT);
        }
        DocumentStatus status = DocumentStatus.valueOf(lockedVersion.getStatus());
        if (action == PublicationAction.PUBLISH) {
            if (!Long.valueOf(lockedVersion.getId()).equals(lockedDocument.getCurrentDraftVersionId())
                    || status != DocumentStatus.READY) {
                throw new BusinessException(ApiErrorCode.DOCUMENT_NOT_READY);
            }
        } else if (action != PublicationAction.ROLLBACK || !ROLLBACKABLE.contains(status)) {
            throw new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT);
        }

        Long fromVersionId = lockedDocument.getCurrentPublishedVersionId();
        long toVersionId = lockedVersion.getId();
        if (mapper.switchPointer(snapshot.tenantId(), snapshot.id(), toVersionId,
                actorUserId, snapshot.rowVersion()) != 1) {
            throw new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT);
        }
        if (fromVersionId != null && fromVersionId != toVersionId) {
            mapper.updateVersionStatus(snapshot.tenantId(), snapshot.id(), fromVersionId, DocumentStatus.ARCHIVED.name());
        }
        mapper.updateVersionStatus(snapshot.tenantId(), snapshot.id(), toVersionId, DocumentStatus.PUBLISHED.name());
        PublicationRecordEntity entity = newRecord(
                expected, fromVersionId, toVersionId, action, actorUserId, requestId);
        mapper.insertRecord(entity);
        if (fromVersionId != null && fromVersionId != toVersionId) {
            mapper.insertCleanup(snapshot.tenantId(), entity.getId(), snapshot.id(), fromVersionId);
        }
        return requireInserted(snapshot.tenantId(), requestId);
    }

    @Override
    @Transactional
    public PublicationRecord disable(PublicationTarget expected, long actorUserId, String requestId) {
        Optional<PublicationRecord> duplicate = findByRequest(expected.document().tenantId(), requestId);
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        KnowledgeDocument snapshot = expected.document();
        DocumentEntity locked = mapper.lockDocument(snapshot.tenantId(), snapshot.knowledgeBaseId(), snapshot.id());
        if (locked == null || locked.getRowVersion() != snapshot.rowVersion()
                || !Long.valueOf(expected.version().id()).equals(locked.getCurrentPublishedVersionId())) {
            throw new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT);
        }
        DocumentVersionEntity version = mapper.lockVersion(
                snapshot.tenantId(), snapshot.knowledgeBaseId(), snapshot.id(), expected.version().id());
        if (version == null || mapper.switchPointer(snapshot.tenantId(), snapshot.id(), null,
                actorUserId, snapshot.rowVersion()) != 1) {
            throw new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT);
        }
        mapper.updateVersionStatus(snapshot.tenantId(), snapshot.id(), version.getId(), DocumentStatus.ARCHIVED.name());
        PublicationRecordEntity entity = newRecord(
                expected, version.getId(), null, PublicationAction.DISABLE, actorUserId, requestId);
        mapper.insertRecord(entity);
        mapper.insertCleanup(snapshot.tenantId(), entity.getId(), snapshot.id(), version.getId());
        return requireInserted(snapshot.tenantId(), requestId);
    }

    private PublicationTarget target(DocumentEntity document, DocumentVersionEntity version) {
        if (document == null || version == null) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_NOT_FOUND);
        }
        return new PublicationTarget(toDocument(document), toVersion(version));
    }

    private PublicationRecordEntity newRecord(
            PublicationTarget target, Long fromVersionId, Long toVersionId, PublicationAction action,
            long actorUserId, String requestId) {
        PublicationRecordEntity entity = new PublicationRecordEntity();
        entity.setTenantId(target.document().tenantId());
        entity.setKnowledgeBaseId(target.document().knowledgeBaseId());
        entity.setDocumentId(target.document().id());
        entity.setFromVersionId(fromVersionId);
        entity.setToVersionId(toVersionId);
        entity.setAction(action.name());
        entity.setActorUserId(actorUserId);
        entity.setRequestId(requestId);
        entity.setChunkCount(target.version().chunkCount());
        entity.setManifestSha256(target.version().indexManifestSha256());
        return entity;
    }

    private PublicationRecord requireInserted(long tenantId, String requestId) {
        return findByRequest(tenantId, requestId)
                .orElseThrow(() -> new IllegalStateException("发布记录插入后无法读取"));
    }

    private PublicationRecord toRecord(PublicationRecordEntity entity) {
        return new PublicationRecord(
                entity.getId(), entity.getTenantId(), entity.getKnowledgeBaseId(), entity.getDocumentId(),
                entity.getFromVersionId(), entity.getToVersionId(), PublicationAction.valueOf(entity.getAction()),
                entity.getActorUserId(), entity.getRequestId(), entity.getChunkCount(),
                entity.getManifestSha256(), entity.getCreatedAt());
    }

    private KnowledgeDocument toDocument(DocumentEntity entity) {
        return new KnowledgeDocument(
                entity.getId(), entity.getTenantId(), entity.getKnowledgeBaseId(), entity.getTitle(),
                entity.getCurrentDraftVersionId(), entity.getCurrentPublishedVersionId(),
                entity.getCreatedBy(), entity.getUpdatedBy(), entity.getRowVersion(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private DocumentVersion toVersion(DocumentVersionEntity entity) {
        return new DocumentVersion(
                entity.getId(), entity.getTenantId(), entity.getKnowledgeBaseId(), entity.getDocumentId(),
                entity.getVersionNumber(), DocumentStatus.valueOf(entity.getStatus()), entity.getOriginalFilename(),
                entity.getFileExtension(), entity.getMimeType(), entity.getFileSize(), entity.getSourceSha256(),
                entity.getSourceObjectKey(), entity.getParsedObjectKey(), entity.getParserVersion(),
                entity.getChunkStrategyVersion(), entity.getEmbeddingModel(), entity.getEmbeddingDimension(),
                entity.getEmbeddingInstructionVersion(), entity.getIndexManifestSha256(), entity.getIndexedAt(),
                Boolean.TRUE.equals(entity.getOcrRequired()), entity.getCorrectionRevision(), entity.getUnitCount(),
                entity.getChunkCount(), entity.getFailureStage(), entity.getLastErrorCode(), entity.getLastErrorMessage(),
                entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}

package com.xjjk.knowledge.publication.release;

import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.persistence.DocumentVersionEntity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisReleaseRepository implements ReleaseRepository {
    private final ReleaseMapper mapper;

    public MybatisReleaseRepository(ReleaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReleaseBaseline lockBaseline(long tenantId, long knowledgeBaseId) {
        return mapper.lockBaseline(tenantId, knowledgeBaseId);
    }

    @Override
    public List<ReleaseItem> items(long releaseId) {
        return mapper.items(releaseId);
    }

    @Override
    public Optional<ReleaseItem> findReadyItem(
            long tenantId, long knowledgeBaseId, long documentId, long versionId) {
        return Optional.ofNullable(mapper.findReadyItem(
                tenantId, knowledgeBaseId, documentId, versionId));
    }

    @Override
    public Optional<KnowledgeRelease> findByRequest(long tenantId, String requestId) {
        return Optional.ofNullable(mapper.findByRequest(tenantId, requestId));
    }

    @Override
    public int nextReleaseNumber(long tenantId, long knowledgeBaseId) {
        return mapper.nextReleaseNumber(tenantId, knowledgeBaseId);
    }

    @Override
    public KnowledgeRelease insert(KnowledgeRelease release) {
        mapper.insertRelease(release);
        KnowledgeRelease inserted = mapper.findByRequest(release.tenantId(), release.requestId());
        if (inserted == null) {
            throw new IllegalStateException("Release 写入后无法读取");
        }
        return inserted;
    }

    @Override
    public void insertItems(long releaseId, List<ReleaseItem> items) {
        items.forEach(item -> mapper.insertItem(releaseId, item));
    }

    @Override
    public void enqueue(KnowledgeRelease release) {
        mapper.insertTask(release);
        String payload = "{\"tenantId\":" + release.tenantId()
                + ",\"knowledgeBaseId\":" + release.knowledgeBaseId()
                + ",\"releaseId\":" + release.id() + "}";
        mapper.insertOutbox(
                UUID.randomUUID().toString(), Long.toString(release.id()), payload, release);
    }

    @Override
    public Optional<KnowledgeRelease> find(long tenantId, long knowledgeBaseId, long releaseId) {
        return Optional.ofNullable(mapper.find(tenantId, knowledgeBaseId, releaseId));
    }

    @Override
    public List<DocumentVersion> changedVersions(KnowledgeRelease release) {
        return mapper.changedVersions(release.id(), release.baseReleaseId()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void activate(KnowledgeRelease release, ReleaseTaskLease lease) {
        KnowledgeRelease locked = mapper.lockRelease(
                release.tenantId(), release.knowledgeBaseId(), release.id());
        ReleaseBaseline baseline = mapper.lockBaseline(release.tenantId(), release.knowledgeBaseId());
        if (locked == null || locked.status() != ReleaseStatus.PREPARING
                || baseline == null
                || !Objects.equals(baseline.currentReleaseId(), release.baseReleaseId())
                || baseline.rowVersion() != release.baseRowVersion()
                || !mapper.isLeaseValid(lease)
                || mapper.countInvalidItems(release.id()) != 0) {
            throw new ReleaseConflictException();
        }
        if (mapper.switchCurrentRelease(
                release.tenantId(), release.knowledgeBaseId(), release.id(),
                release.baseReleaseId(), release.baseRowVersion(), release.createdBy()) != 1) {
            throw new ReleaseConflictException();
        }
        if (release.baseReleaseId() != null) {
            mapper.markSuperseded(release.baseReleaseId());
        }
        if (mapper.markActive(release.id()) != 1) {
            throw new ReleaseConflictException();
        }
        mapper.archiveReplacedItems(release.baseReleaseId(), release.id());
        mapper.markItemsPublished(release.id());
        mapper.syncDocumentPointers(
                release.tenantId(), release.knowledgeBaseId(), release.id(), release.createdBy());
        if (release.baseReleaseId() != null) {
            mapper.insertSupersededCleanup(release.baseReleaseId(), release.id());
        }
    }

    @Override
    @Transactional
    public void markConflict(KnowledgeRelease release, ReleaseTaskLease lease) {
        if (!mapper.isLeaseValid(lease)) {
            throw new IllegalStateException("Release 冲突回写时任务租约已经丢失");
        }
        mapper.markTerminal(release.id(), ReleaseStatus.CONFLICT.name(), "RELEASE_BASE_CONFLICT");
        mapper.insertFailedCleanup(release.id(), release.baseReleaseId());
    }

    @Override
    @Transactional
    public void markFailed(KnowledgeRelease release, String failureCode) {
        mapper.markTerminal(release.id(), ReleaseStatus.FAILED.name(), failureCode);
        mapper.insertFailedCleanup(release.id(), release.baseReleaseId());
    }

    @Override
    public Long currentReleaseId(long tenantId, long knowledgeBaseId) {
        return mapper.currentReleaseId(tenantId, knowledgeBaseId);
    }

    private DocumentVersion toDomain(DocumentVersionEntity entity) {
        return new DocumentVersion(
                entity.getId(), entity.getTenantId(), entity.getKnowledgeBaseId(), entity.getDocumentId(),
                entity.getVersionNumber(), DocumentStatus.valueOf(entity.getStatus()),
                entity.getOriginalFilename(), entity.getFileExtension(), entity.getMimeType(),
                entity.getFileSize(), entity.getSourceSha256(), entity.getSourceObjectKey(),
                entity.getParsedObjectKey(), entity.getParserVersion(), entity.getChunkStrategyVersion(),
                entity.getEmbeddingModel(), entity.getEmbeddingDimension(),
                entity.getEmbeddingInstructionVersion(), entity.getIndexManifestSha256(), entity.getIndexedAt(),
                Boolean.TRUE.equals(entity.getOcrRequired()), entity.getCorrectionRevision(),
                entity.getUnitCount(), entity.getChunkCount(), entity.getFailureStage(),
                entity.getLastErrorCode(), entity.getLastErrorMessage(), entity.getCreatedBy(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}

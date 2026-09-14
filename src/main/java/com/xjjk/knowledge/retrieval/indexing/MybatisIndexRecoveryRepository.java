package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.persistence.DocumentVersionEntity;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingProperties;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
class MybatisIndexRecoveryRepository implements IndexRecoveryRepository {
    private final IndexRecoveryMapper mapper;
    private final EmbeddingProperties embeddingProperties;

    MybatisIndexRecoveryRepository(
            IndexRecoveryMapper mapper, EmbeddingProperties embeddingProperties) {
        this.mapper = mapper;
        this.embeddingProperties = embeddingProperties;
    }

    @Override
    public List<IndexRecoveryTarget> listCurrentTargets() {
        List<IndexRecoveryTarget> targets = new ArrayList<>();
        mapper.listCurrentPublishedVersions().stream()
                .map(this::toDomain)
                .map(version -> new IndexRecoveryTarget(IndexLayer.PUBLISHED, version))
                .forEach(targets::add);
        mapper.listCurrentDraftVersions().stream()
                .map(this::toDomain)
                .map(version -> new IndexRecoveryTarget(IndexLayer.DRAFT, version))
                .forEach(targets::add);
        return List.copyOf(targets);
    }

    @Override
    public boolean upgradeEmbeddingContract(DocumentVersion expectedVersion) {
        return mapper.upgradeEmbeddingContract(
                expectedVersion.id(), expectedVersion.tenantId(), expectedVersion.documentId(),
                expectedVersion.indexManifestSha256(), expectedVersion.embeddingModel(),
                expectedVersion.embeddingDimension(), expectedVersion.embeddingInstructionVersion(),
                embeddingProperties.getModel(), embeddingProperties.getDimension(),
                embeddingProperties.getInstructionVersion()) == 1;
    }

    private DocumentVersion toDomain(DocumentVersionEntity entity) {
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

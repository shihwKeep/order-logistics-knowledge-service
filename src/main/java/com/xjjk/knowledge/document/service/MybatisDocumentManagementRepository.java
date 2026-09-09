package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.processing.TextNormalizer;
import com.xjjk.knowledge.document.task.IngestionTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisDocumentManagementRepository implements DocumentManagementRepository {
    private final DocumentManagementMapper mapper;
    private final TextNormalizer normalizer;

    public MybatisDocumentManagementRepository(DocumentManagementMapper mapper, TextNormalizer normalizer) {
        this.mapper = mapper;
        this.normalizer = normalizer;
    }

    @Override
    public List<DocumentUnit> listUnits(
            long tenantId, long documentId, long versionId, Boolean lowConfidence, int offset, int limit) {
        return mapper.listUnits(tenantId, documentId, versionId, lowConfidence, offset, limit)
                .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public DocumentUnit correctUnit(
            long tenantId, long knowledgeBaseId, long documentId, long versionId, long unitId,
            String correctedText, long actorId, String requestId) {
        Integer currentRevision = mapper.lockCorrectionRevision(tenantId, knowledgeBaseId, documentId, versionId);
        DocumentUnitRow row = mapper.findUnit(tenantId, knowledgeBaseId, documentId, versionId, unitId);
        if (currentRevision == null || row == null) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_UNIT_NOT_FOUND);
        }
        int revision = currentRevision + 1;
        mapper.insertRevision(
                tenantId, documentId, versionId, unitId, revision,
                row.getEffectiveText(), correctedText, actorId, requestId);
        mapper.updateEffectiveText(
                tenantId, documentId, versionId, unitId, correctedText, normalizer.sha256(correctedText));
        mapper.deleteChunks(tenantId, versionId);
        mapper.markCorrection(tenantId, knowledgeBaseId, documentId, versionId, revision);
        mapper.insertCorrectionTask(
                tenantId, knowledgeBaseId, documentId, versionId,
                "CHUNK:" + tenantId + ":" + versionId + ":" + revision);
        mapper.insertOutbox(
                UUID.randomUUID().toString(), tenantId, Long.toString(versionId),
                "{\"tenantId\":" + tenantId + ",\"versionId\":" + versionId + "}");
        return toDomain(mapper.findUnit(tenantId, knowledgeBaseId, documentId, versionId, unitId));
    }

    @Override
    public Optional<IngestionTask> latestTask(long tenantId, long versionId) {
        return Optional.ofNullable(mapper.findLatestTask(tenantId, versionId));
    }

    @Override
    @Transactional
    public boolean retry(long tenantId, long versionId) {
        IngestionTask task = mapper.findLatestTask(tenantId, versionId);
        if (task == null || mapper.retryTask(tenantId, task.id()) != 1) {
            return false;
        }
        mapper.insertOutbox(
                UUID.randomUUID().toString(), tenantId, Long.toString(versionId),
                "{\"tenantId\":" + tenantId + ",\"versionId\":" + versionId + "}");
        return true;
    }

    private DocumentUnit toDomain(DocumentUnitRow row) {
        return new DocumentUnit(
                row.getId(), row.getTenantId(), row.getDocumentId(), row.getVersionId(),
                row.getUnitType(), row.getUnitIndex(), row.getLocationLabel(), row.getTitlePath(),
                row.getRawText(), row.getEffectiveText(), row.getOcrConfidence(),
                Boolean.TRUE.equals(row.getLowConfidence()), row.getCorrectionRevision());
    }
}

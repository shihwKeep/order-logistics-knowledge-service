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
    public List<DocumentChunkView> listChunks(
            long tenantId, long documentId, long versionId, int offset, int limit) {
        return mapper.listChunks(tenantId, documentId, versionId, offset, limit);
    }

    @Override
    @Transactional
    public DocumentUnit correctUnit(
            long tenantId, long knowledgeBaseId, long documentId, long versionId, long unitId,
            String correctedText, long actorId, String requestId) {
        CorrectionVersionState state = mapper.lockCorrectionState(
                tenantId, knowledgeBaseId, documentId, versionId);
        if (state == null) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_UNIT_NOT_FOUND);
        }
        // 发布版本是不可变快照。修改正文必须形成新草稿版本，不能让已发布索引与 MySQL 漂移。
        if ("PUBLISHED".equals(state.getStatus()) || "ARCHIVED".equals(state.getStatus())) {
            throw new BusinessException(
                    ApiErrorCode.PUBLICATION_CONFLICT, "已发布或归档版本不能原地校正，请创建新版本");
        }
        DocumentUnitRow row = mapper.findUnit(tenantId, knowledgeBaseId, documentId, versionId, unitId);
        if (row == null) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_UNIT_NOT_FOUND);
        }
        int revision = state.getCorrectionRevision() + 1;
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

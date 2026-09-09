package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.document.task.IngestionTask;
import java.util.List;
import java.util.Optional;

public interface DocumentManagementRepository {
    List<DocumentUnit> listUnits(long tenantId, long documentId, long versionId, Boolean lowConfidence, int offset, int limit);
    List<DocumentChunkView> listChunks(long tenantId, long documentId, long versionId, int offset, int limit);
    DocumentUnit correctUnit(long tenantId, long knowledgeBaseId, long documentId, long versionId, long unitId,
                             String correctedText, long actorId, String requestId);
    Optional<IngestionTask> latestTask(long tenantId, long versionId);
    boolean retry(long tenantId, long versionId);
}

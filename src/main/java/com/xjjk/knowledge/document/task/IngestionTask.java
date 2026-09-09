package com.xjjk.knowledge.document.task;

import java.time.LocalDateTime;

public record IngestionTask(
        long id,
        long tenantId,
        long knowledgeBaseId,
        long documentId,
        long versionId,
        String stage,
        String status,
        int retryCount,
        LocalDateTime nextRunAt,
        String leaseToken,
        String lockedBy,
        LocalDateTime lockedUntil,
        String lastErrorCode) {}

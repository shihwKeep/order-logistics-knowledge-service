package com.xjjk.knowledge.publication.release;

import java.time.LocalDateTime;

public record ReleaseTask(
        long id,
        long releaseId,
        long tenantId,
        long knowledgeBaseId,
        String status,
        int retryCount,
        LocalDateTime nextRunAt,
        String leaseToken,
        String lockedBy,
        LocalDateTime lockedUntil,
        String lastErrorCode) {
}

package com.xjjk.knowledge.publication;

public record PublicationCleanupTask(
        long id, long tenantId, long documentId, long versionId, int retryCount) {
}

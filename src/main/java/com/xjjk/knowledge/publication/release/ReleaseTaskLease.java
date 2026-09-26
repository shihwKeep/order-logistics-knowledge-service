package com.xjjk.knowledge.publication.release;

public record ReleaseTaskLease(
        long taskId,
        long releaseId,
        long tenantId,
        long knowledgeBaseId,
        String leaseToken) {
}

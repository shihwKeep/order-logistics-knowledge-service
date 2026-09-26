package com.xjjk.knowledge.publication.cleanup;

import com.xjjk.knowledge.retrieval.model.IndexLayer;

/** 一条已被 Worker 租约领取的派生索引清理任务。 */
public record DerivedCleanupTask(
        long id,
        long tenantId,
        long knowledgeBaseId,
        long documentId,
        long versionId,
        IndexLayer layer,
        String reason,
        int retryCount,
        String leaseToken) {
}

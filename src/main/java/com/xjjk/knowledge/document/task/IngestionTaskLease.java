package com.xjjk.knowledge.document.task;

/** 只有持有当前 leaseToken 的 Worker 才能完成或重试任务。 */
public record IngestionTaskLease(
        long taskId,
        long tenantId,
        long knowledgeBaseId,
        long documentId,
        long versionId,
        String stage,
        String leaseToken) {}

package com.xjjk.knowledge.publication.release;

import java.time.LocalDateTime;

/** 知识库一次完整、不可变的线上文档版本清单。 */
public record KnowledgeRelease(
        long id,
        long tenantId,
        long knowledgeBaseId,
        int releaseNumber,
        ReleaseStatus status,
        Long baseReleaseId,
        String requestId,
        String manifestSha256,
        int baseRowVersion,
        long createdBy,
        String failureCode,
        LocalDateTime createdAt,
        LocalDateTime activatedAt,
        LocalDateTime updatedAt) {

    public static KnowledgeRelease preparing(
            long tenantId,
            long knowledgeBaseId,
            int releaseNumber,
            Long baseReleaseId,
            String requestId,
            String manifestSha256,
            int baseRowVersion,
            long createdBy) {
        return new KnowledgeRelease(
                0L, tenantId, knowledgeBaseId, releaseNumber, ReleaseStatus.PREPARING,
                baseReleaseId, requestId, manifestSha256, baseRowVersion, createdBy,
                null, null, null, null);
    }

    public KnowledgeRelease withId(long newId) {
        return new KnowledgeRelease(
                newId, tenantId, knowledgeBaseId, releaseNumber, status, baseReleaseId,
                requestId, manifestSha256, baseRowVersion, createdBy, failureCode,
                createdAt, activatedAt, updatedAt);
    }
}

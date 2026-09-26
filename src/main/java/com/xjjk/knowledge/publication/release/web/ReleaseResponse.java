package com.xjjk.knowledge.publication.release.web;

import com.xjjk.knowledge.publication.release.KnowledgeRelease;
import com.xjjk.knowledge.publication.release.ReleaseStatus;
import java.time.LocalDateTime;

public record ReleaseResponse(
        long id,
        long knowledgeBaseId,
        int releaseNumber,
        ReleaseStatus status,
        Long baseReleaseId,
        String manifestSha256,
        long createdBy,
        String failureCode,
        LocalDateTime createdAt,
        LocalDateTime activatedAt,
        LocalDateTime updatedAt) {

    public static ReleaseResponse from(KnowledgeRelease release) {
        return new ReleaseResponse(
                release.id(), release.knowledgeBaseId(), release.releaseNumber(), release.status(),
                release.baseReleaseId(), release.manifestSha256(), release.createdBy(),
                release.failureCode(), release.createdAt(), release.activatedAt(), release.updatedAt());
    }
}

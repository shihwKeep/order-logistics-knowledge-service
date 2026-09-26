package com.xjjk.knowledge.publication.release.web;

import com.xjjk.knowledge.publication.release.KnowledgeRelease;
import com.xjjk.knowledge.publication.release.ReleaseItem;
import com.xjjk.knowledge.publication.release.ReleaseStatus;
import java.time.LocalDateTime;
import java.util.List;

public record ReleaseDetailResponse(
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
        LocalDateTime updatedAt,
        List<ItemResponse> items) {

    public static ReleaseDetailResponse from(
            KnowledgeRelease release, List<ReleaseItem> items) {
        return new ReleaseDetailResponse(
                release.id(), release.knowledgeBaseId(), release.releaseNumber(), release.status(),
                release.baseReleaseId(), release.manifestSha256(), release.createdBy(),
                release.failureCode(), release.createdAt(), release.activatedAt(), release.updatedAt(),
                items.stream().map(ItemResponse::from).toList());
    }

    public record ItemResponse(
            long documentId,
            long versionId,
            String contentManifestSha256) {
        static ItemResponse from(ReleaseItem item) {
            return new ItemResponse(
                    item.documentId(), item.versionId(), item.contentManifestSha256());
        }
    }
}

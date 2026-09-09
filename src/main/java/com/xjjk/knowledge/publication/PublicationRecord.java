package com.xjjk.knowledge.publication;

import java.time.LocalDateTime;

public record PublicationRecord(
        long id,
        long tenantId,
        long knowledgeBaseId,
        long documentId,
        Long fromVersionId,
        Long toVersionId,
        PublicationAction action,
        long actorUserId,
        String requestId,
        int chunkCount,
        String manifestSha256,
        LocalDateTime createdAt) {
}

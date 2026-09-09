package com.xjjk.knowledge.document.domain;

import java.time.LocalDateTime;

/** 文档的不可变来源版本；解析派生字段只描述该版本的处理结果。 */
public record DocumentVersion(
        long id,
        long tenantId,
        long knowledgeBaseId,
        long documentId,
        int versionNumber,
        DocumentStatus status,
        String originalFilename,
        String fileExtension,
        String mimeType,
        long fileSize,
        String sourceSha256,
        String sourceObjectKey,
        String parsedObjectKey,
        String parserVersion,
        String chunkStrategyVersion,
        String embeddingModel,
        Integer embeddingDimension,
        String embeddingInstructionVersion,
        String indexManifestSha256,
        LocalDateTime indexedAt,
        boolean ocrRequired,
        int correctionRevision,
        int unitCount,
        int chunkCount,
        String failureStage,
        String lastErrorCode,
        String lastErrorMessage,
        long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

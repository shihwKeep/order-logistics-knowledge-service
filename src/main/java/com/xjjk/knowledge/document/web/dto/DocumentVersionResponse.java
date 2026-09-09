package com.xjjk.knowledge.document.web.dto;

import com.xjjk.knowledge.document.domain.DocumentVersion;

import java.time.LocalDateTime;

public record DocumentVersionResponse(
        long id,
        int versionNumber,
        String status,
        String originalFilename,
        String mimeType,
        long fileSize,
        boolean ocrRequired,
        int unitCount,
        int chunkCount,
        String lastErrorCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static DocumentVersionResponse from(DocumentVersion version) {
        return new DocumentVersionResponse(
                version.id(), version.versionNumber(), version.status().name(),
                version.originalFilename(), version.mimeType(), version.fileSize(),
                version.ocrRequired(), version.unitCount(), version.chunkCount(),
                version.lastErrorCode(), version.createdAt(), version.updatedAt());
    }
}

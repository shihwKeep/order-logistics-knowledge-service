package com.xjjk.knowledge.document.web.dto;

import com.xjjk.knowledge.document.service.DocumentChunkView;

import java.time.LocalDateTime;

public record DocumentChunkResponse(
        long id,
        long unitId,
        int chunkIndex,
        String titlePath,
        String content,
        int tokenCount,
        String locationJson,
        LocalDateTime createdAt) {
    public static DocumentChunkResponse from(DocumentChunkView chunk) {
        return new DocumentChunkResponse(
                chunk.id(), chunk.unitId(), chunk.chunkIndex(), chunk.titlePath(), chunk.content(),
                chunk.tokenCount(), chunk.locationJson(), chunk.createdAt());
    }
}

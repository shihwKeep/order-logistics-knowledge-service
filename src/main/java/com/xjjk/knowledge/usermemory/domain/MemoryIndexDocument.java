package com.xjjk.knowledge.usermemory.domain;

import java.time.Instant;
import java.util.Objects;

/** Agent MySQL 已验证后交给索引层的不可变记忆文档。 */
public record MemoryIndexDocument(
        String memoryId,
        long tenantId,
        long userId,
        long memoryGeneration,
        long memoryVersion,
        String sourceType,
        String category,
        String canonicalKey,
        String content,
        double confidence,
        Instant expiresAt
) {
    public MemoryIndexDocument {
        if (memoryId == null || memoryId.isBlank() || memoryId.length() > 64
                || tenantId <= 0 || userId <= 0 || memoryGeneration <= 0
                || memoryVersion <= 0
                || sourceType == null || sourceType.isBlank() || sourceType.length() > 24
                || category == null || category.isBlank() || category.length() > 48
                || canonicalKey == null || canonicalKey.isBlank() || canonicalKey.length() > 128
                || content == null || content.isBlank() || content.length() > 512
                || !Double.isFinite(confidence) || confidence < 0D || confidence > 1D) {
            throw new IllegalArgumentException("用户记忆索引文档不合法");
        }
        Objects.requireNonNull(sourceType);
    }
}

package com.xjjk.knowledge.usermemory.web;

import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexOperation;

import java.math.BigDecimal;
import java.time.Instant;

public record IndexMemoryEventRequest(
        String eventId,
        MemoryIndexOperation operation,
        long memoryGeneration,
        String memoryId,
        long memoryVersion,
        String sourceType,
        String category,
        String canonicalKey,
        String content,
        double confidence,
        Instant expiresAt
) {
    public IndexMemoryEventRequest {
        if (eventId == null || eventId.isBlank() || eventId.length() > 64
                || operation == null || memoryGeneration <= 0) {
            throw new IllegalArgumentException("记忆索引事件基础字段不合法");
        }
        if (operation == MemoryIndexOperation.UPSERT) {
            // 复用领域构造器执行全部字段上限与数值校验。
            new MemoryIndexDocument(memoryId, 1, 1, memoryGeneration,
                    memoryVersion, sourceType, category, canonicalKey,
                    content, confidence, expiresAt);
        } else if (operation == MemoryIndexOperation.DELETE) {
            if (memoryId == null || memoryId.isBlank() || memoryId.length() > 64
                    || memoryVersion <= 0 || sourceType != null || category != null
                    || canonicalKey != null || content != null || confidence != 0D
                    || expiresAt != null) {
                throw new IllegalArgumentException("单条删除事件字段组合不合法");
            }
        } else if (memoryId != null || memoryVersion != 0
                || sourceType != null || category != null || canonicalKey != null
                || content != null || confidence != 0D || expiresAt != null) {
            throw new IllegalArgumentException("范围删除事件不能携带记忆正文");
        }
    }

    public MemoryIndexDocument toDocument(long tenantId, long userId) {
        if (operation != MemoryIndexOperation.UPSERT) {
            throw new IllegalStateException("只有 UPSERT 可以转换为索引文档");
        }
        return new MemoryIndexDocument(
                memoryId, tenantId, userId, memoryGeneration, memoryVersion,
                sourceType, category, canonicalKey, content, confidence, expiresAt);
    }

    public String payloadDigest() {
        String canonical = String.join("\n",
                eventId,
                operation.name(),
                Long.toString(memoryGeneration),
                value(memoryId),
                Long.toString(memoryVersion),
                value(sourceType),
                value(category),
                value(canonicalKey),
                MemoryPayloadDigests.sha256(value(content)),
                BigDecimal.valueOf(confidence).stripTrailingZeros().toPlainString(),
                expiresAt == null ? "" : expiresAt.toString());
        return MemoryPayloadDigests.sha256(canonical);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}

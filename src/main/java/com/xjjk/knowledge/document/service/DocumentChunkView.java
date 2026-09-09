package com.xjjk.knowledge.document.service;

import java.time.LocalDateTime;

/** 管理台 Chunk 预览，不包含向量或内部对象存储地址。 */
public record DocumentChunkView(
        long id,
        long unitId,
        int chunkIndex,
        String titlePath,
        String content,
        int tokenCount,
        String locationJson,
        LocalDateTime createdAt) {
}

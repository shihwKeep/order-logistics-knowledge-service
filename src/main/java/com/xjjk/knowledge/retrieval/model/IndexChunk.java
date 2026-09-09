package com.xjjk.knowledge.retrieval.model;

/** ES 与 Milvus 共同使用的稳定 Chunk 元数据。 */
public record IndexChunk(
        String chunkId,
        long tenantId,
        long knowledgeBaseId,
        long documentId,
        long versionId,
        int chunkIndex,
        String documentTitle,
        String titlePath,
        String content,
        String contentSha256,
        String locationJson) {
}

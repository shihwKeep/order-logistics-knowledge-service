package com.xjjk.knowledge.publication.release;

/** Release 中一个文档所固定的版本及其索引内容摘要。 */
public record ReleaseItem(
        long releaseId,
        long tenantId,
        long knowledgeBaseId,
        long documentId,
        long versionId,
        String contentManifestSha256) {
}

package com.xjjk.knowledge.retrieval.service;

public record VersionReference(long documentId, long versionId) {
    String key() {
        return documentId + ":" + versionId;
    }
}

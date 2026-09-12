package com.xjjk.knowledge.usermemory.web;

public record RetrieveUserMemoryRequest(String query, long memoryGeneration) {
    public RetrieveUserMemoryRequest {
        if (query == null || query.isBlank() || query.length() > 2000
                || memoryGeneration <= 0) {
            throw new IllegalArgumentException("用户记忆召回请求不合法");
        }
    }

    public String payloadDigest() {
        return MemoryPayloadDigests.sha256(
                memoryGeneration + "\n" + MemoryPayloadDigests.sha256(query.trim()));
    }
}

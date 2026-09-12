package com.xjjk.knowledge.usermemory.index;

import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;

public record MemorySearchHit(
        MemoryIndexDocument document,
        double score,
        String source
) {
    public MemorySearchHit {
        if (document == null || !Double.isFinite(score)
                || source == null || source.isBlank()) {
            throw new IllegalArgumentException("用户记忆检索命中不合法");
        }
    }
}

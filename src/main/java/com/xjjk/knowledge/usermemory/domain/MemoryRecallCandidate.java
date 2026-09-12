package com.xjjk.knowledge.usermemory.domain;

import java.util.Set;

public record MemoryRecallCandidate(
        String memoryId,
        long memoryVersion,
        double score,
        int rank,
        Set<String> sources
) {
    public MemoryRecallCandidate {
        if (memoryId == null || memoryId.isBlank() || memoryVersion <= 0
                || !Double.isFinite(score) || rank <= 0
                || sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("用户记忆召回候选不合法");
        }
        sources = Set.copyOf(sources);
    }
}

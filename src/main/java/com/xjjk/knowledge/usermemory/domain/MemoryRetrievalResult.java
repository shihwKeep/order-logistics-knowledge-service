package com.xjjk.knowledge.usermemory.domain;

import java.util.List;

public record MemoryRetrievalResult(
        List<MemoryRecallCandidate> candidates,
        String strategyVersion,
        String degradationMode,
        String resultCode
) {
    public MemoryRetrievalResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (strategyVersion == null || strategyVersion.isBlank()
                || degradationMode == null || degradationMode.isBlank()
                || resultCode == null || resultCode.isBlank()) {
            throw new IllegalArgumentException("用户记忆召回结果不合法");
        }
    }
}

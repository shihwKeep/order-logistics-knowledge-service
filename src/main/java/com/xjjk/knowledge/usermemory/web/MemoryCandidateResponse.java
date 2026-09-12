package com.xjjk.knowledge.usermemory.web;

import com.xjjk.knowledge.usermemory.domain.MemoryRecallCandidate;

import java.util.Set;

public record MemoryCandidateResponse(
        String memoryId,
        long memoryVersion,
        double score,
        int rank,
        Set<String> sources
) {
    static MemoryCandidateResponse from(MemoryRecallCandidate candidate) {
        return new MemoryCandidateResponse(
                candidate.memoryId(), candidate.memoryVersion(), candidate.score(),
                candidate.rank(), candidate.sources());
    }
}

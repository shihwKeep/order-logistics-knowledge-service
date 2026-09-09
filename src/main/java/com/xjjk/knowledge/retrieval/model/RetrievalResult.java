package com.xjjk.knowledge.retrieval.model;

import java.util.List;

public record RetrievalResult(
        boolean answerable,
        List<RankedEvidence> evidences,
        String strategyVersion,
        DegradationMode degradationMode,
        String resultCode,
        int vectorCandidateCount,
        int keywordCandidateCount,
        int fusedCandidateCount) {
    public RetrievalResult {
        evidences = List.copyOf(evidences);
    }
}

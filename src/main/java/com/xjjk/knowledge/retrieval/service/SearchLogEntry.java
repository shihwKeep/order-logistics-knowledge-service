package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.retrieval.model.DegradationMode;

record SearchLogEntry(
        long tenantId,
        long userId,
        String requestId,
        String strategyVersion,
        DegradationMode degradationMode,
        String resultCode,
        boolean answerable,
        int vectorCandidateCount,
        int keywordCandidateCount,
        int fusedCandidateCount,
        int evidenceCount,
        long totalDurationMs) {
}

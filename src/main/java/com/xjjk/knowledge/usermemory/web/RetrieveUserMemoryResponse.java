package com.xjjk.knowledge.usermemory.web;

import com.xjjk.knowledge.usermemory.domain.MemoryRetrievalResult;

import java.util.List;

public record RetrieveUserMemoryResponse(
        List<MemoryCandidateResponse> candidates,
        String strategyVersion,
        String degradationMode,
        String resultCode
) {
    static RetrieveUserMemoryResponse from(MemoryRetrievalResult result) {
        return new RetrieveUserMemoryResponse(
                result.candidates().stream().map(MemoryCandidateResponse::from).toList(),
                result.strategyVersion(), result.degradationMode(), result.resultCode());
    }
}

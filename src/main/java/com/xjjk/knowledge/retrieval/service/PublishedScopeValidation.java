package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.retrieval.model.RankedEvidence;

import java.util.List;

/** 线上证据终审结果；Release变化时证据必须为空并由上层重试。 */
public record PublishedScopeValidation(
        boolean releaseChanged,
        List<RankedEvidence> evidences) {
    public PublishedScopeValidation {
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        if (releaseChanged && !evidences.isEmpty()) {
            throw new IllegalArgumentException("Release变化时不能返回证据");
        }
    }
}

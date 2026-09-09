package com.xjjk.knowledge.retrieval.model;

import java.util.Set;

/** RRF 融合或 Cross-Encoder 精排后的候选证据。 */
public record RankedEvidence(
        IndexChunk chunk,
        double score,
        double rrfScore,
        Set<RecallSource> sources,
        int bestRank) {
    public RankedEvidence {
        sources = Set.copyOf(sources);
    }

    public RankedEvidence withScore(double relevanceScore) {
        return new RankedEvidence(chunk, relevanceScore, rrfScore, sources, bestRank);
    }
}

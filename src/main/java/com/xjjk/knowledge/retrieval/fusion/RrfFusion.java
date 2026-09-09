package com.xjjk.knowledge.retrieval.fusion;

import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import com.xjjk.knowledge.retrieval.model.RecallCandidate;
import com.xjjk.knowledge.retrieval.model.RecallSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 倒数排名融合（RRF）。ES 和 Milvus 的原始分数不可直接比较，因此只使用各自名次，
 * 公式为每路 {@code weight / (60 + rank)}。
 */
@Component
public class RrfFusion {
    private static final int RANK_CONSTANT = 60;

    public List<RankedEvidence> fuse(
            List<RecallCandidate> vectorCandidates,
            List<RecallCandidate> keywordCandidates,
            int limit,
            double vectorWeight,
            double keywordWeight) {
        if (limit <= 0 || vectorWeight <= 0 || keywordWeight <= 0) {
            throw new IllegalArgumentException("RRF 数量和权重必须大于 0");
        }
        Map<String, State> states = new LinkedHashMap<>();
        add(states, vectorCandidates, RecallSource.VECTOR, vectorWeight);
        add(states, keywordCandidates, RecallSource.KEYWORD, keywordWeight);
        return states.values().stream()
                .map(State::toEvidence)
                .sorted(Comparator.comparingDouble(RankedEvidence::rrfScore).reversed()
                        .thenComparingInt(RankedEvidence::bestRank)
                        .thenComparing(item -> item.chunk().chunkId()))
                .limit(limit)
                .toList();
    }

    private void add(
            Map<String, State> states,
            List<RecallCandidate> candidates,
            RecallSource expectedSource,
            double weight) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        Set<String> seenInChannel = new HashSet<>();
        int rank = 0;
        for (RecallCandidate candidate : candidates) {
            if (candidate == null || candidate.chunk() == null || candidate.source() != expectedSource) {
                throw new IllegalArgumentException("RRF 候选来源与检索通道不一致");
            }
            String chunkId = candidate.chunk().chunkId();
            if (!seenInChannel.add(chunkId)) {
                continue;
            }
            rank++;
            State state = states.computeIfAbsent(chunkId, ignored -> new State(candidate.chunk()));
            state.score += weight / (RANK_CONSTANT + rank);
            state.bestRank = Math.min(state.bestRank, rank);
            state.sources.add(expectedSource);
        }
    }

    private static final class State {
        private final IndexChunk chunk;
        private final Set<RecallSource> sources = EnumSet.noneOf(RecallSource.class);
        private double score;
        private int bestRank = Integer.MAX_VALUE;

        private State(IndexChunk chunk) {
            this.chunk = chunk;
        }

        private RankedEvidence toEvidence() {
            return new RankedEvidence(chunk, score, score, sources, bestRank);
        }
    }
}

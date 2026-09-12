package com.xjjk.knowledge.usermemory.fusion;

import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;
import com.xjjk.knowledge.usermemory.index.MemorySearchHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 记忆双路召回的倒数排名融合，原始 ES 与向量分数不直接比较。 */
@Component
public class MemoryRrfFusion {
    private static final int RANK_CONSTANT = 60;

    public List<FusedMemoryHit> fuse(
            List<MemorySearchHit> vector,
            List<MemorySearchHit> keyword,
            int limit,
            double vectorWeight,
            double keywordWeight) {
        if (limit <= 0 || vectorWeight <= 0D || keywordWeight <= 0D) {
            throw new IllegalArgumentException("记忆 RRF 参数不合法");
        }
        Map<String, State> states = new LinkedHashMap<>();
        add(states, vector, "VECTOR", vectorWeight);
        add(states, keyword, "KEYWORD", keywordWeight);
        return states.values().stream()
                .map(State::toHit)
                .sorted(Comparator.comparingDouble(FusedMemoryHit::rrfScore).reversed()
                        .thenComparingInt(FusedMemoryHit::bestRank)
                        .thenComparing(hit -> hit.document().memoryId()))
                .limit(limit).toList();
    }

    private void add(
            Map<String, State> states,
            List<MemorySearchHit> hits,
            String source,
            double weight) {
        if (hits == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        int rank = 0;
        for (MemorySearchHit hit : hits) {
            if (hit == null || !source.equals(hit.source())
                    || !seen.add(hit.document().memoryId())) {
                continue;
            }
            rank++;
            State state = states.computeIfAbsent(
                    hit.document().memoryId(), ignored -> new State(hit.document()));
            if (hit.document().memoryVersion() > state.document.memoryVersion()) {
                state.document = hit.document();
            }
            state.score += weight / (RANK_CONSTANT + rank);
            state.bestRank = Math.min(state.bestRank, rank);
            state.sources.add(source);
        }
    }

    public record FusedMemoryHit(
            MemoryIndexDocument document,
            double rrfScore,
            int bestRank,
            Set<String> sources) {
        public FusedMemoryHit {
            sources = Set.copyOf(sources);
        }
    }

    private static final class State {
        private MemoryIndexDocument document;
        private double score;
        private int bestRank = Integer.MAX_VALUE;
        private final Set<String> sources = new LinkedHashSet<>();

        private State(MemoryIndexDocument document) {
            this.document = document;
        }

        private FusedMemoryHit toHit() {
            return new FusedMemoryHit(document, score, bestRank, sources);
        }
    }
}

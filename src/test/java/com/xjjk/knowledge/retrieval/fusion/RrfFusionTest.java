package com.xjjk.knowledge.retrieval.fusion;

import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.RecallCandidate;
import com.xjjk.knowledge.retrieval.model.RecallSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    @Test
    void deduplicatesByStableChunkIdAndAccumulatesWeightedRanks() {
        RrfFusion fusion = new RrfFusion();
        var vector = List.of(candidate("b", RecallSource.VECTOR), candidate("a", RecallSource.VECTOR));
        var keyword = List.of(candidate("a", RecallSource.KEYWORD), candidate("c", RecallSource.KEYWORD));

        var result = fusion.fuse(vector, keyword, 20, 1.0D, 2.0D);

        assertThat(result).extracting(evidence -> evidence.chunk().chunkId())
                .containsExactly("a", "c", "b");
        assertThat(result.getFirst().rrfScore())
                .isEqualTo(1.0D / 62D + 2.0D / 61D);
        assertThat(result.getFirst().sources())
                .containsExactlyInAnyOrder(RecallSource.VECTOR, RecallSource.KEYWORD);
    }

    @Test
    void resolvesEqualScoresByBestRankThenStableIdAndIgnoresChannelDuplicates() {
        RrfFusion fusion = new RrfFusion();
        var vector = List.of(
                candidate("z", RecallSource.VECTOR),
                candidate("a", RecallSource.VECTOR),
                candidate("a", RecallSource.VECTOR));
        var keyword = List.of(candidate("b", RecallSource.KEYWORD));

        var result = fusion.fuse(vector, keyword, 3, 1.0D, 1.0D);

        assertThat(result).extracting(evidence -> evidence.chunk().chunkId())
                .containsExactly("b", "z", "a");
        assertThat(result.get(2).rrfScore()).isEqualTo(1.0D / 62D);
    }

    private RecallCandidate candidate(String id, RecallSource source) {
        return new RecallCandidate(new IndexChunk(
                id, 1L, 2L, 3L, 4L, 0, "退款规则", "售后", "正文-" + id, "hash-" + id, "{}"),
                0.9D, source);
    }
}

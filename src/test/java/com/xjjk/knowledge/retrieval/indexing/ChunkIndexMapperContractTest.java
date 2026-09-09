package com.xjjk.knowledge.retrieval.indexing;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkIndexMapperContractTest {
    @Test
    void readyTransitionIsGuardedByIndexingStatusAndCorrectionRevision() throws Exception {
        Update update = ChunkIndexMapper.class.getMethod(
                        "markReady", long.class, long.class, long.class, int.class, int.class,
                        String.class, int.class, String.class, String.class, Long.class, String.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", update.value()).replaceAll("\\s+", " ");

        assertThat(sql).contains("status='INDEXING'")
                .contains("correction_revision=#{correctionRevision}")
                .contains("t.lease_token=#{leaseToken}")
                .contains("t.locked_until>=CURRENT_TIMESTAMP(3)");
    }
}

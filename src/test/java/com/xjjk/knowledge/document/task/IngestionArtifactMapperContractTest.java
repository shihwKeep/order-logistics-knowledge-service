package com.xjjk.knowledge.document.task;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionArtifactMapperContractTest {
    @Test
    void retryAndFailureTransitionsRequireCurrentRevisionAndLease() throws Exception {
        String prepare = sql("prepareIndexAttempt", long.class, long.class, long.class,
                int.class, long.class, String.class);
        String fail = sql("markFailedIfOwned", long.class, long.class, long.class,
                int.class, long.class, String.class, String.class, String.class);

        assertThat(prepare)
                .contains("status='INDEXING'")
                .contains("v.status='FAILED' AND v.failure_stage='INDEX'")
                .contains("correction_revision=#{correctionRevision}")
                .contains("lease_token=#{leaseToken}");
        assertThat(fail)
                .contains("correction_revision=#{correctionRevision}")
                .contains("lease_token=#{leaseToken}")
                .contains("locked_until>=CURRENT_TIMESTAMP(3)");
    }

    private String sql(String method, Class<?>... parameters) throws Exception {
        Update update = IngestionArtifactMapper.class.getMethod(method, parameters).getAnnotation(Update.class);
        return String.join(" ", update.value()).replaceAll("\\s+", " ");
    }
}

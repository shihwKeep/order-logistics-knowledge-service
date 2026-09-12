package com.xjjk.knowledge.usermemory.web;

import com.xjjk.knowledge.usermemory.domain.MemoryIndexOperation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexMemoryEventRequestTest {

    @Test
    void producesStableDigestAndBuildsDocumentFromTrustedHeaders() {
        IndexMemoryEventRequest request = new IndexMemoryEventRequest(
                "event-1", MemoryIndexOperation.UPSERT, 3, "m-1", 2,
                "AUTO_EXTRACT", "WORK_COMMON_SCOPE", "work.common_scope",
                "用户常用工作范围是 Java 开发", 0.95,
                Instant.parse("2027-03-11T00:00:00Z"));

        assertThat(request.payloadDigest()).hasSize(64);
        assertThat(request.toDocument(1, 74680).tenantId()).isEqualTo(1);
        assertThat(request.toDocument(1, 74680).userId()).isEqualTo(74680);
    }

    @Test
    void rejectsBodyForScopeDeleteAndMissingBodyForUpsert() {
        assertThatThrownBy(() -> new IndexMemoryEventRequest(
                "event-1", MemoryIndexOperation.CLEAR_GENERATION, 3,
                null, 0, null, null, null, "unexpected", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexMemoryEventRequest(
                "event-1", MemoryIndexOperation.UPSERT, 3,
                "m-1", 2, null, null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.xjjk.knowledge.usermemory.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryIndexDocumentTest {

    @Test
    void acceptsACompleteScopedDocument() {
        new MemoryIndexDocument(
                "7c4ce70e-276b-495c-bb25-8c74a3032a3e",
                1L, 74680L, 3L, 2L,
                "AUTO_EXTRACT", "WORK_COMMON_SCOPE", "work.common_scope",
                "用户常用工作范围是 Java 开发", 0.95D,
                Instant.parse("2027-03-11T00:00:00Z"));
    }

    @Test
    void rejectsMissingOwnerOrUnsafeContent() {
        assertThatThrownBy(() -> new MemoryIndexDocument(
                "7c4ce70e-276b-495c-bb25-8c74a3032a3e",
                0L, 74680L, 3L, 2L,
                "AUTO_EXTRACT", "WORK_COMMON_SCOPE", "work.common_scope",
                "Java", 0.95D, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new MemoryIndexDocument(
                "7c4ce70e-276b-495c-bb25-8c74a3032a3e",
                1L, 74680L, 3L, 2L,
                "AUTO_EXTRACT", "WORK_COMMON_SCOPE", "work.common_scope",
                "x".repeat(513), 0.95D, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

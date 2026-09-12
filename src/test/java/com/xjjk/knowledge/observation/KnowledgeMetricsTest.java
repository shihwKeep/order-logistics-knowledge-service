package com.xjjk.knowledge.observation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeMetricsTest {

    @Test
    void recordsOnlyBoundedOperationalTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KnowledgeMetrics metrics = new KnowledgeMetrics(registry);

        metrics.recordRetrieval("NONE", "OK", 18);
        metrics.recordIngestion("INDEX", "SUCCESS", 35);
        metrics.recordIngestionRejected();
        metrics.recordUserMemoryIndex("UPSERT", "SUCCESS");
        metrics.recordUserMemoryChannel("ES", "AVAILABLE");
        metrics.recordUserMemoryRecall("KEYWORD_ONLY", "OK");
        metrics.recordUserMemoryCandidates("FINAL", 3);

        assertThat(registry.get("knowledge.retrieval.duration").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("knowledge.ingestion.duration").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("knowledge.ingestion.rejected").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("knowledge.user.memory.index.operation")
                .tags("operation", "UPSERT", "outcome", "SUCCESS")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("knowledge.user.memory.recall.channel")
                .tags("channel", "ES", "outcome", "AVAILABLE")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("knowledge.user.memory.recall")
                .tags("degradation", "KEYWORD_ONLY", "result", "OK")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("knowledge.user.memory.recall.candidates")
                .tags("stage", "FINAL").summary().totalAmount()).isEqualTo(3);
        registry.getMeters().forEach(meter -> assertThat(
                meter.getId().getTags().stream().map(tag -> tag.getKey()).toList())
                .allMatch(Set.of("outcome", "degradation", "stage", "operation",
                        "channel", "result")::contains));
    }

    @Test
    void collapsesUnknownValuesInsteadOfCreatingUnboundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KnowledgeMetrics metrics = new KnowledgeMetrics(registry);

        metrics.recordRetrieval("external-value", "request-specific-value", 1);
        metrics.recordIngestion("tenant-specific-stage", "stack-trace", 1);
        metrics.recordUserMemoryIndex("tenant-specific-operation", "stack-trace");
        metrics.recordUserMemoryChannel("request-specific-channel", "stack-trace");
        metrics.recordUserMemoryRecall("request-specific-mode", "request-specific-result");
        metrics.recordUserMemoryCandidates("request-specific-stage", 1);

        assertThat(registry.get("knowledge.retrieval.duration")
                .tags("degradation", "UNKNOWN", "outcome", "UNKNOWN")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("knowledge.ingestion.duration")
                .tags("stage", "UNKNOWN", "outcome", "FAILED")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("knowledge.user.memory.index.operation")
                .tags("operation", "UNKNOWN", "outcome", "FAILURE")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("knowledge.user.memory.recall.channel")
                .tags("channel", "UNKNOWN", "outcome", "UNAVAILABLE")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("knowledge.user.memory.recall")
                .tags("degradation", "UNKNOWN", "result", "UNKNOWN")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("knowledge.user.memory.recall.candidates")
                .tags("stage", "FINAL").summary().count()).isEqualTo(1);
    }
}

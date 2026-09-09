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

        assertThat(registry.get("knowledge.retrieval.duration").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("knowledge.ingestion.duration").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("knowledge.ingestion.rejected").counter().count())
                .isEqualTo(1);
        registry.getMeters().forEach(meter -> assertThat(
                meter.getId().getTags().stream().map(tag -> tag.getKey()).toList())
                .allMatch(Set.of("outcome", "degradation", "stage")::contains));
    }

    @Test
    void collapsesUnknownValuesInsteadOfCreatingUnboundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KnowledgeMetrics metrics = new KnowledgeMetrics(registry);

        metrics.recordRetrieval("external-value", "request-specific-value", 1);
        metrics.recordIngestion("tenant-specific-stage", "stack-trace", 1);

        assertThat(registry.get("knowledge.retrieval.duration")
                .tags("degradation", "UNKNOWN", "outcome", "UNKNOWN")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("knowledge.ingestion.duration")
                .tags("stage", "UNKNOWN", "outcome", "FAILED")
                .timer().count()).isEqualTo(1);
    }
}

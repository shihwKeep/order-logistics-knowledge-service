package com.xjjk.knowledge.observation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/** 只记录低基数运行指标；禁止把租户、用户、请求号、问题或正文放入标签。 */
@Component
public class KnowledgeMetrics {
    private static final Set<String> DEGRADATIONS = Set.of(
            "NONE", "VECTOR_ONLY", "KEYWORD_ONLY", "RERANKER_STRICT_RRF",
            "NO_RELIABLE_EVIDENCE", "ALL_RECALL_UNAVAILABLE");
    private static final Set<String> RETRIEVAL_OUTCOMES = Set.of(
            "OK", "NO_RELEVANT_EVIDENCE", "NO_RELIABLE_EVIDENCE",
            "KNOWLEDGE_SERVICE_UNAVAILABLE");
    private static final Set<String> INGESTION_STAGES = Set.of("CLAIM", "PARSE", "CHUNK", "INDEX");
    private static final Set<String> INGESTION_OUTCOMES = Set.of("SUCCESS", "FAILED", "UNCLAIMED");

    private final MeterRegistry registry;

    public KnowledgeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRetrieval(String degradation, String outcome, long durationMillis) {
        Timer.builder("knowledge.retrieval.duration")
                .description("Knowledge retrieval latency and result")
                .tags("degradation", bounded(degradation, DEGRADATIONS, "UNKNOWN"),
                        "outcome", bounded(outcome, RETRIEVAL_OUTCOMES, "UNKNOWN"))
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMillis)));
    }

    public void recordIngestion(String stage, String outcome, long durationMillis) {
        Timer.builder("knowledge.ingestion.duration")
                .description("Document ingestion task latency and result")
                .tags("stage", bounded(stage, INGESTION_STAGES, "UNKNOWN"),
                        "outcome", bounded(outcome, INGESTION_OUTCOMES, "FAILED"))
                .register(registry)
                .record(Duration.ofMillis(Math.max(0, durationMillis)));
    }

    public void recordIngestionRejected() {
        registry.counter("knowledge.ingestion.rejected").increment();
    }

    private String bounded(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }
}

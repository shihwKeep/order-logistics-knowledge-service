package com.xjjk.knowledge.observation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.DistributionSummary;
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
    private static final Set<String> MEMORY_INDEX_OPERATIONS = Set.of(
            "UPSERT", "DELETE", "DELETE_EXPLICIT_SCOPE", "CLEAR_GENERATION");
    private static final Set<String> MEMORY_INDEX_OUTCOMES = Set.of("SUCCESS", "FAILURE");
    private static final Set<String> MEMORY_CHANNELS = Set.of("ES", "MILVUS");
    private static final Set<String> MEMORY_CHANNEL_OUTCOMES = Set.of("AVAILABLE", "UNAVAILABLE");
    private static final Set<String> MEMORY_DEGRADATIONS = Set.of(
            "NONE", "KEYWORD_ONLY", "VECTOR_ONLY", "ALL_RECALL_UNAVAILABLE", "DISABLED");
    private static final Set<String> MEMORY_RESULTS = Set.of("OK", "NO_CANDIDATE", "DISABLED");
    private static final Set<String> MEMORY_CANDIDATE_STAGES = Set.of("FUSED", "FINAL");

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

    /** 记录一次线上检索快照包含的精确文档版本数，不使用租户或知识库等高基数标签。 */
    public void recordReleaseScopeSize(int versionCount) {
        DistributionSummary.builder("knowledge.retrieval.release.scope.versions")
                .description("Document version count in an active release retrieval scope")
                .register(registry)
                .record(Math.max(0, versionCount));
    }

    /** 记录因发布指针变化触发的整轮检索重试次数。 */
    public void recordReleaseRetry() {
        registry.counter("knowledge.retrieval.release.retry").increment();
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

    public void recordUserMemoryIndex(String operation, String outcome) {
        registry.counter("knowledge.user.memory.index.operation",
                        "operation", bounded(operation, MEMORY_INDEX_OPERATIONS, "UNKNOWN"),
                        "outcome", bounded(outcome, MEMORY_INDEX_OUTCOMES, "FAILURE"))
                .increment();
    }

    public void recordUserMemoryChannel(String channel, String outcome) {
        registry.counter("knowledge.user.memory.recall.channel",
                        "channel", bounded(channel, MEMORY_CHANNELS, "UNKNOWN"),
                        "outcome", bounded(outcome, MEMORY_CHANNEL_OUTCOMES, "UNAVAILABLE"))
                .increment();
    }

    public void recordUserMemoryRecall(String degradation, String result) {
        registry.counter("knowledge.user.memory.recall",
                        "degradation", bounded(degradation, MEMORY_DEGRADATIONS, "UNKNOWN"),
                        "result", bounded(result, MEMORY_RESULTS, "UNKNOWN"))
                .increment();
    }

    public void recordUserMemoryCandidates(String stage, int count) {
        DistributionSummary.builder("knowledge.user.memory.recall.candidates")
                .description("User memory candidate counts at bounded retrieval stages")
                .tag("stage", bounded(stage, MEMORY_CANDIDATE_STAGES, "FINAL"))
                .register(registry)
                .record(Math.max(0, count));
    }

    private String bounded(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }
}

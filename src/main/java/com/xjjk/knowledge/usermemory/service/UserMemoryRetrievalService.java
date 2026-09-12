package com.xjjk.knowledge.usermemory.service;

import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import com.xjjk.knowledge.retrieval.model.RecallSource;
import com.xjjk.knowledge.retrieval.rerank.Reranker;
import com.xjjk.knowledge.retrieval.rerank.RerankerUnavailableException;
import com.xjjk.knowledge.usermemory.config.UserMemoryIndexProperties;
import com.xjjk.knowledge.usermemory.domain.MemoryRecallCandidate;
import com.xjjk.knowledge.usermemory.domain.MemoryRetrievalResult;
import com.xjjk.knowledge.usermemory.fusion.MemoryRrfFusion;
import com.xjjk.knowledge.usermemory.fusion.MemoryRrfFusion.FusedMemoryHit;
import com.xjjk.knowledge.usermemory.index.MemoryKeywordIndex;
import com.xjjk.knowledge.usermemory.index.MemorySearchHit;
import com.xjjk.knowledge.usermemory.index.MemoryVectorIndex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** 用户记忆双路召回；只返回候选 ID 和排序信号，不返回正文。 */
@Service
public class UserMemoryRetrievalService {
    private final UserMemoryIndexProperties properties;
    private final MemoryKeywordIndex keywordIndex;
    private final MemoryVectorIndex vectorIndex;
    private final EmbeddingClient embeddings;
    private final Reranker reranker;
    private final Executor executor;
    private final MemoryRrfFusion fusion = new MemoryRrfFusion();

    @Autowired
    public UserMemoryRetrievalService(
            UserMemoryIndexProperties properties,
            MemoryKeywordIndex keywordIndex,
            MemoryVectorIndex vectorIndex,
            EmbeddingClient embeddings,
            Reranker reranker,
            @Qualifier("userMemoryRetrievalExecutor") Executor executor) {
        this.properties = properties;
        this.keywordIndex = keywordIndex;
        this.vectorIndex = vectorIndex;
        this.embeddings = embeddings;
        this.reranker = reranker;
        this.executor = executor;
    }

    public MemoryRetrievalResult retrieve(
            long tenantId, long userId, long generation, String query) {
        if (tenantId <= 0 || userId <= 0 || generation <= 0
                || query == null || query.isBlank()) {
            throw new IllegalArgumentException("用户记忆召回参数不合法");
        }
        var strategy = properties.getRetrieval();
        if (!properties.isEnabled()) {
            return result(List.of(), "DISABLED", "DISABLED");
        }
        long timeoutMillis = strategy.getTimeout().toMillis();
        CompletableFuture<ChannelResult> keywordFuture = CompletableFuture
                .supplyAsync(() -> recallKeyword(
                        tenantId, userId, generation, query), executor)
                .completeOnTimeout(ChannelResult.unavailable(), timeoutMillis, TimeUnit.MILLISECONDS)
                .exceptionally(ignored -> ChannelResult.unavailable());
        CompletableFuture<ChannelResult> vectorFuture = CompletableFuture
                .supplyAsync(() -> recallVector(
                        tenantId, userId, generation, query), executor)
                .completeOnTimeout(ChannelResult.unavailable(), timeoutMillis, TimeUnit.MILLISECONDS)
                .exceptionally(ignored -> ChannelResult.unavailable());

        ChannelResult keyword = keywordFuture.join();
        ChannelResult vector = vectorFuture.join();
        if (!keyword.available && !vector.available) {
            return result(List.of(), "ALL_RECALL_UNAVAILABLE", "NO_CANDIDATE");
        }
        String degradation = keyword.available
                ? (vector.available ? "NONE" : "KEYWORD_ONLY") : "VECTOR_ONLY";
        List<FusedMemoryHit> fused = fusion.fuse(
                vector.hits, keyword.hits, strategy.getRrfTopK(),
                strategy.getVectorWeight(), strategy.getKeywordWeight());
        List<ScoredMemory> scored = rerank(query, fused);
        List<MemoryRecallCandidate> candidates = new ArrayList<>();
        int rank = 0;
        for (ScoredMemory item : scored.stream()
                .limit(strategy.getFinalTopK()).toList()) {
            rank++;
            candidates.add(new MemoryRecallCandidate(
                    item.fused.document().memoryId(),
                    item.fused.document().memoryVersion(),
                    item.score, rank, item.fused.sources()));
        }
        return result(candidates, degradation,
                candidates.isEmpty() ? "NO_CANDIDATE" : "OK");
    }

    private ChannelResult recallKeyword(
            long tenantId, long userId, long generation, String query) {
        try {
            return ChannelResult.available(keywordIndex.search(
                    tenantId, userId, generation, query,
                    properties.getRetrieval().getEsTopK()));
        } catch (RuntimeException exception) {
            return ChannelResult.unavailable();
        }
    }

    private ChannelResult recallVector(
            long tenantId, long userId, long generation, String query) {
        try {
            List<Float> queryVector = embeddings.embedQuery(query);
            List<MemorySearchHit> hits = vectorIndex.search(
                    tenantId, userId, generation, queryVector,
                    properties.getRetrieval().getMilvusTopK()).stream()
                    .filter(hit -> hit.score()
                            >= properties.getRetrieval().getMinVectorScore())
                    .toList();
            return ChannelResult.available(hits);
        } catch (RuntimeException exception) {
            return ChannelResult.unavailable();
        }
    }

    private List<ScoredMemory> rerank(String query, List<FusedMemoryHit> fused) {
        if (fused.isEmpty()) {
            return List.of();
        }
        if (!properties.getRetrieval().isRerankEnabled()) {
            return fused.stream().map(hit -> new ScoredMemory(hit, hit.rrfScore())).toList();
        }
        Map<String, FusedMemoryHit> byId = new HashMap<>();
        List<RankedEvidence> evidence = fused.stream().map(hit -> {
            byId.put(hit.document().memoryId(), hit);
            Set<RecallSource> sources = EnumSet.noneOf(RecallSource.class);
            if (hit.sources().contains("VECTOR")) sources.add(RecallSource.VECTOR);
            if (hit.sources().contains("KEYWORD")) sources.add(RecallSource.KEYWORD);
            IndexChunk chunk = new IndexChunk(
                    hit.document().memoryId(), hit.document().tenantId(), 0, 0,
                    hit.document().memoryVersion(), 0, "", "",
                    hit.document().content(), "", null);
            return new RankedEvidence(
                    chunk, hit.rrfScore(), hit.rrfScore(), sources, hit.bestRank());
        }).toList();
        try {
            return reranker.rerank(query, evidence).stream()
                    .filter(item -> item.score()
                            >= properties.getRetrieval().getRerankerScoreThreshold())
                    .map(item -> new ScoredMemory(
                            byId.get(item.chunk().chunkId()), item.score()))
                    .filter(item -> item.fused != null)
                    .toList();
        } catch (RerankerUnavailableException exception) {
            return fused.stream().map(hit -> new ScoredMemory(hit, hit.rrfScore())).toList();
        }
    }

    private MemoryRetrievalResult result(
            List<MemoryRecallCandidate> candidates,
            String degradation,
            String code) {
        return new MemoryRetrievalResult(
                candidates, properties.getRetrieval().getStrategyVersion(),
                degradation, code);
    }

    private record ChannelResult(boolean available, List<MemorySearchHit> hits) {
        private static ChannelResult available(List<MemorySearchHit> hits) {
            return new ChannelResult(true, hits == null ? List.of() : List.copyOf(hits));
        }
        private static ChannelResult unavailable() {
            return new ChannelResult(false, List.of());
        }
    }

    private record ScoredMemory(FusedMemoryHit fused, double score) {
    }
}

package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.retrieval.fusion.RrfFusion;
import com.xjjk.knowledge.retrieval.index.KeywordIndex;
import com.xjjk.knowledge.retrieval.index.VectorIndex;
import com.xjjk.knowledge.retrieval.model.DegradationMode;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import com.xjjk.knowledge.retrieval.model.RecallCandidate;
import com.xjjk.knowledge.retrieval.model.RetrievalResult;
import com.xjjk.knowledge.retrieval.rerank.Reranker;
import com.xjjk.knowledge.retrieval.rerank.RerankerProperties;
import com.xjjk.knowledge.retrieval.rerank.RerankerUnavailableException;
import com.xjjk.knowledge.observation.KnowledgeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** 在线发布层检索：双路召回、RRF、BGE、MySQL 发布指针终审和故障矩阵。 */
@Service
public class HybridRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final EmbeddingClient embeddings;
    private final KeywordIndex keywordIndex;
    private final VectorIndex vectorIndex;
    private final RrfFusion fusion;
    private final Reranker reranker;
    private final RerankerProperties rerankerProperties;
    private final RetrievalProperties properties;
    private final PublishedVersionValidator publishedValidator;
    private final DraftVersionValidator draftValidator;
    private final SearchLogRecorder searchLogs;
    private final KnowledgeMetrics metrics;

    public HybridRetrievalService(
            EmbeddingClient embeddings,
            KeywordIndex keywordIndex,
            VectorIndex vectorIndex,
            RrfFusion fusion,
            Reranker reranker,
            RerankerProperties rerankerProperties,
            RetrievalProperties properties,
            PublishedVersionValidator publishedValidator,
            DraftVersionValidator draftValidator,
            SearchLogRecorder searchLogs,
            KnowledgeMetrics metrics) {
        this.embeddings = embeddings;
        this.keywordIndex = keywordIndex;
        this.vectorIndex = vectorIndex;
        this.fusion = fusion;
        this.reranker = reranker;
        this.rerankerProperties = rerankerProperties;
        this.properties = properties;
        this.publishedValidator = publishedValidator;
        this.draftValidator = draftValidator;
        this.searchLogs = searchLogs;
        this.metrics = metrics;
    }

    public RetrievalResult retrieve(
            long tenantId, long userId, String requestId, String question, List<Long> knowledgeBaseIds) {
        return retrieveLayer(
                tenantId, userId, requestId, question, knowledgeBaseIds, IndexLayer.PUBLISHED);
    }

    public RetrievalResult retrieveAdmin(
            long tenantId, long userId, String requestId, String question,
            List<Long> knowledgeBaseIds, IndexLayer layer) {
        if (layer == null) throw new IllegalArgumentException("检索层不能为空");
        return retrieveLayer(tenantId, userId, requestId, question, knowledgeBaseIds, layer);
    }

    private RetrievalResult retrieveLayer(
            long tenantId, long userId, String requestId, String question,
            List<Long> knowledgeBaseIds, IndexLayer layer) {
        if (tenantId <= 0 || userId <= 0 || requestId == null || requestId.isBlank()
                || question == null || question.isBlank()) {
            throw new IllegalArgumentException("检索身份、请求号和问题不能为空");
        }
        long startedAt = System.nanoTime();
        List<RecallCandidate> vectorCandidates = List.of();
        List<RecallCandidate> keywordCandidates = List.of();
        boolean vectorAvailable = true;
        boolean keywordAvailable = true;

        try {
            List<Float> queryVector = embeddings.embedQuery(question);
            vectorCandidates = vectorIndex.search(
                    layer, tenantId, knowledgeBaseIds, queryVector, properties.getRecallTopK());
        } catch (RuntimeException exception) {
            vectorAvailable = false;
            log.warn("knowledge_vector_recall_unavailable requestId={}, exceptionType={}",
                    requestId, exception.getClass().getSimpleName());
        }
        try {
            keywordCandidates = keywordIndex.search(
                    layer, tenantId, knowledgeBaseIds, question, properties.getRecallTopK());
        } catch (RuntimeException exception) {
            keywordAvailable = false;
            log.warn("knowledge_keyword_recall_unavailable requestId={}, exceptionType={}",
                    requestId, exception.getClass().getSimpleName());
        }

        if (!vectorAvailable && !keywordAvailable) {
            validateLayer(layer, tenantId, List.of());
            recordSafely(new SearchLogEntry(
                    tenantId, userId, requestId, properties.getVersion(), DegradationMode.ALL_RECALL_UNAVAILABLE,
                    ApiErrorCode.KNOWLEDGE_SERVICE_UNAVAILABLE.code(), false, 0, 0, 0, 0, elapsed(startedAt)));
            metrics.recordRetrieval(
                    DegradationMode.ALL_RECALL_UNAVAILABLE.name(),
                    ApiErrorCode.KNOWLEDGE_SERVICE_UNAVAILABLE.code(), elapsed(startedAt));
            throw new BusinessException(ApiErrorCode.KNOWLEDGE_SERVICE_UNAVAILABLE);
        }

        List<RankedEvidence> fused = fusion.fuse(
                vectorCandidates, keywordCandidates, properties.getFusionTopK(),
                properties.getVectorWeight(), properties.getKeywordWeight());
        DegradationMode degradation = vectorAvailable
                ? (keywordAvailable ? DegradationMode.NONE : DegradationMode.VECTOR_ONLY)
                : DegradationMode.KEYWORD_ONLY;
        String resultCode = "OK";
        List<RankedEvidence> selected;
        try {
            selected = fused.isEmpty() ? List.of() : reranker.rerank(question, fused).stream()
                    .filter(candidate -> candidate.score() >= rerankerProperties.getScoreThreshold())
                    .toList();
        } catch (RerankerUnavailableException exception) {
            if (vectorAvailable && keywordAvailable) {
                degradation = DegradationMode.RERANKER_STRICT_RRF;
                selected = fused.stream()
                        .filter(candidate -> candidate.sources().size() == 2)
                        .filter(candidate -> candidate.rrfScore() >= properties.getStrictRrfThreshold())
                        .toList();
            } else {
                degradation = DegradationMode.NO_RELIABLE_EVIDENCE;
                resultCode = "NO_RELIABLE_EVIDENCE";
                selected = List.of();
            }
        }

        // 终审必须在精排/降级之后、返回调用方之前执行。
        List<RankedEvidence> evidences = validateLayer(layer, tenantId, selected).stream()
                .limit(properties.getFinalTopK())
                .toList();
        boolean answerable = !evidences.isEmpty();
        if (!answerable && "OK".equals(resultCode)) {
            resultCode = "NO_RELEVANT_EVIDENCE";
        }
        RetrievalResult result = new RetrievalResult(
                answerable, evidences, properties.getVersion(), degradation, resultCode,
                vectorCandidates.size(), keywordCandidates.size(), fused.size());
        recordSafely(new SearchLogEntry(
                tenantId, userId, requestId, properties.getVersion(), degradation, resultCode, answerable,
                vectorCandidates.size(), keywordCandidates.size(), fused.size(), evidences.size(), elapsed(startedAt)));
        metrics.recordRetrieval(degradation.name(), resultCode, elapsed(startedAt));
        return result;
    }

    private List<RankedEvidence> validateLayer(
            IndexLayer layer, long tenantId, List<RankedEvidence> candidates) {
        return layer == IndexLayer.PUBLISHED
                ? publishedValidator.validate(tenantId, candidates)
                : draftValidator.validate(tenantId, candidates);
    }

    private void recordSafely(SearchLogEntry entry) {
        try {
            searchLogs.record(entry);
        } catch (RuntimeException exception) {
            log.warn("knowledge_search_log_failed requestId={}, exceptionType={}",
                    entry.requestId(), exception.getClass().getSimpleName());
        }
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}

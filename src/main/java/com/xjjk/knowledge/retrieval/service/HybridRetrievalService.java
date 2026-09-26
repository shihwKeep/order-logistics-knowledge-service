package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.cloud.budget.CloudModelBudgetExceededException;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.retrieval.fusion.RrfFusion;
import com.xjjk.knowledge.retrieval.index.KeywordIndex;
import com.xjjk.knowledge.retrieval.index.VectorIndex;
import com.xjjk.knowledge.retrieval.model.DegradationMode;
import com.xjjk.knowledge.retrieval.model.DocumentVersionRef;
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
    private final ActiveReleaseScopeLoader scopeLoader;
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
            ActiveReleaseScopeLoader scopeLoader,
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
        this.scopeLoader = scopeLoader;
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
        ActiveReleaseScope scope = layer == IndexLayer.PUBLISHED
                ? scopeLoader.load(tenantId, knowledgeBaseIds)
                : null;
        if (scope != null && scope.isEmpty()) {
            return emptyResult(tenantId, userId, requestId, startedAt);
        }

        QueryEmbedding embedding = prepareEmbedding(question);
        return retrieveAttempt(
                tenantId, userId, requestId, question, knowledgeBaseIds, layer,
                scope, embedding, 0, startedAt);
    }

    private RetrievalResult retrieveAttempt(
            long tenantId,
            long userId,
            String requestId,
            String question,
            List<Long> knowledgeBaseIds,
            IndexLayer layer,
            ActiveReleaseScope scope,
            QueryEmbedding embedding,
            int attempt,
            long startedAt) {
        List<RecallCandidate> vectorCandidates = List.of();
        List<RecallCandidate> keywordCandidates = List.of();
        boolean vectorAvailable = embedding.available();
        boolean keywordAvailable = true;
        List<DocumentVersionRef> allowedVersions = scope == null ? List.of() : scope.versions();

        if (vectorAvailable) {
            try {
                vectorCandidates = vectorIndex.search(
                        layer, tenantId, knowledgeBaseIds, allowedVersions,
                        embedding.vector(), properties.getRecallTopK());
            } catch (RuntimeException exception) {
                vectorAvailable = false;
                log.warn("knowledge_vector_recall_unavailable requestId={}, exceptionType={}",
                        requestId, exception.getClass().getSimpleName());
            }
        }
        try {
            keywordCandidates = keywordIndex.search(
                    layer, tenantId, knowledgeBaseIds, allowedVersions,
                    question, properties.getRecallTopK());
        } catch (RuntimeException exception) {
            keywordAvailable = false;
            log.warn("knowledge_keyword_recall_unavailable requestId={}, exceptionType={}",
                    requestId, exception.getClass().getSimpleName());
        }

        if (!vectorAvailable && !keywordAvailable) {
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
        } catch (CloudModelBudgetExceededException exception) {
            throw new BusinessException(ApiErrorCode.KNOWLEDGE_MODEL_BUDGET_EXHAUSTED, exception);
        } catch (RerankerUnavailableException exception) {
            // RRF 只能说明候选在两路召回中的排名，不能证明正文足以回答问题。
            // 精排不可用时必须保守拒答，避免把仅有词面相似性的内容交给大模型。
            degradation = DegradationMode.NO_RELIABLE_EVIDENCE;
            resultCode = "NO_RELIABLE_EVIDENCE";
            selected = List.of();
        }

        // 终审必须在精排/降级之后、返回调用方之前执行。
        List<RankedEvidence> validated;
        if (layer == IndexLayer.PUBLISHED) {
            PublishedScopeValidation validation = publishedValidator.validate(tenantId, scope, selected);
            if (validation.releaseChanged()) {
                if (attempt >= 1) {
                    throw new BusinessException(ApiErrorCode.KNOWLEDGE_RELEASE_CHANGING);
                }
                ActiveReleaseScope latestScope = scopeLoader.load(tenantId, knowledgeBaseIds);
                log.info(
                        "knowledge_release_scope_changed requestId={}, previousReleaseCount={}, latestReleaseCount={}",
                        requestId, scope.releaseIds().size(), latestScope.releaseIds().size());
                if (latestScope.isEmpty()) {
                    return emptyResult(tenantId, userId, requestId, startedAt);
                }
                return retrieveAttempt(
                        tenantId, userId, requestId, question, knowledgeBaseIds, layer,
                        latestScope, embedding, attempt + 1, startedAt);
            }
            validated = validation.evidences();
        } else {
            validated = draftValidator.validate(tenantId, selected);
        }
        List<RankedEvidence> evidences = validated.stream()
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

    private QueryEmbedding prepareEmbedding(String question) {
        try {
            return new QueryEmbedding(embeddings.embedQuery(question), true);
        } catch (CloudModelBudgetExceededException exception) {
            throw new BusinessException(ApiErrorCode.KNOWLEDGE_MODEL_BUDGET_EXHAUSTED, exception);
        } catch (RuntimeException exception) {
            log.warn("knowledge_query_embedding_unavailable exceptionType={}",
                    exception.getClass().getSimpleName());
            return new QueryEmbedding(List.of(), false);
        }
    }

    private RetrievalResult emptyResult(
            long tenantId, long userId, String requestId, long startedAt) {
        String resultCode = "NO_RELEVANT_EVIDENCE";
        RetrievalResult result = new RetrievalResult(
                false, List.of(), properties.getVersion(), DegradationMode.NONE,
                resultCode, 0, 0, 0);
        recordSafely(new SearchLogEntry(
                tenantId, userId, requestId, properties.getVersion(), DegradationMode.NONE,
                resultCode, false, 0, 0, 0, 0, elapsed(startedAt)));
        metrics.recordRetrieval(DegradationMode.NONE.name(), resultCode, elapsed(startedAt));
        return result;
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

    private record QueryEmbedding(List<Float> vector, boolean available) {
        private QueryEmbedding {
            vector = vector == null ? List.of() : List.copyOf(vector);
        }
    }
}

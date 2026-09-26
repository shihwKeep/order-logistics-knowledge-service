package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.cloud.budget.CloudModelBudgetExceededException;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingUnavailableException;
import com.xjjk.knowledge.retrieval.fusion.RrfFusion;
import com.xjjk.knowledge.retrieval.index.IndexVerification;
import com.xjjk.knowledge.retrieval.index.KeywordIndex;
import com.xjjk.knowledge.retrieval.index.VectorIndex;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import com.xjjk.knowledge.retrieval.model.RecallCandidate;
import com.xjjk.knowledge.retrieval.model.RecallSource;
import com.xjjk.knowledge.retrieval.model.DegradationMode;
import com.xjjk.knowledge.retrieval.model.RetrievalResult;
import com.xjjk.knowledge.retrieval.rerank.Reranker;
import com.xjjk.knowledge.retrieval.rerank.RerankerProperties;
import com.xjjk.knowledge.retrieval.rerank.RerankerUnavailableException;
import com.xjjk.knowledge.observation.KnowledgeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class HybridRetrievalServiceTest {

    @Test
    void runsTop30RrfTop20RerankPublishValidationAndTop5() {
        Fixture fixture = new Fixture();
        fixture.vector.result = List.of(candidate("shared", RecallSource.VECTOR));
        fixture.keyword.result = List.of(candidate("shared", RecallSource.KEYWORD));
        fixture.reranker = (query, values) -> values.stream().map(value -> value.withScore(0.9D)).toList();
        HybridRetrievalService service = fixture.service();

        var result = service.retrieve(1L, 10567L, "request-1", "签收后几天能退款", List.of(2L));

        assertThat(result.answerable()).isTrue();
        assertThat(result.evidences()).hasSize(1);
        assertThat(result.degradationMode()).isEqualTo(DegradationMode.NONE);
        assertThat(fixture.vector.lastTopK).isEqualTo(30);
        assertThat(fixture.keyword.lastTopK).isEqualTo(30);
        verify(fixture.validator).validate(eq(1L), eq(fixture.defaultScope), anyList());
    }

    @Test
    void returnsNotAnswerableWhenEveryRerankScoreIsBelowThreshold() {
        Fixture fixture = new Fixture();
        fixture.vector.result = List.of(candidate("v", RecallSource.VECTOR));
        fixture.keyword.result = List.of(candidate("k", RecallSource.KEYWORD));
        fixture.reranker = (query, values) -> values.stream().map(value -> value.withScore(0.1D)).toList();

        var result = fixture.service().retrieve(1L, 10567L, "request-2", "问题", List.of());

        assertThat(result.answerable()).isFalse();
        assertThat(result.resultCode()).isEqualTo("NO_RELEVANT_EVIDENCE");
        verify(fixture.validator).validate(eq(1L), eq(fixture.defaultScope), anyList());
    }

    @Test
    void keepsVectorWithBgeWhenElasticsearchFails() {
        Fixture fixture = new Fixture();
        fixture.keyword.failure = new IllegalStateException("es down");
        fixture.vector.result = List.of(candidate("v", RecallSource.VECTOR));
        fixture.reranker = (query, values) -> values.stream().map(value -> value.withScore(0.8D)).toList();

        var result = fixture.service().retrieve(1L, 10567L, "request-3", "问题", List.of());

        assertThat(result.answerable()).isTrue();
        assertThat(result.degradationMode()).isEqualTo(DegradationMode.VECTOR_ONLY);
    }

    @Test
    void keepsKeywordWithBgeWhenEmbeddingFails() {
        Fixture fixture = new Fixture();
        fixture.embeddingFailure = true;
        fixture.keyword.result = List.of(candidate("k", RecallSource.KEYWORD));
        fixture.reranker = (query, values) -> values.stream().map(value -> value.withScore(0.8D)).toList();

        var result = fixture.service().retrieve(1L, 10567L, "request-4", "问题", List.of());

        assertThat(result.answerable()).isTrue();
        assertThat(result.degradationMode()).isEqualTo(DegradationMode.KEYWORD_ONLY);
    }

    @Test
    void rerankerFailureFailsClosedEvenWhenBothRecallChannelsFindTheSameCandidate() {
        Fixture fixture = new Fixture();
        fixture.vector.result = List.of(candidate("shared", RecallSource.VECTOR), candidate("vector", RecallSource.VECTOR));
        fixture.keyword.result = List.of(candidate("shared", RecallSource.KEYWORD), candidate("keyword", RecallSource.KEYWORD));
        fixture.reranker = (query, values) -> { throw new RerankerUnavailableException("down"); };

        var result = fixture.service().retrieve(1L, 10567L, "request-5", "问题", List.of());

        assertThat(result.answerable()).isFalse();
        assertThat(result.degradationMode()).isEqualTo(DegradationMode.NO_RELIABLE_EVIDENCE);
        assertThat(result.resultCode()).isEqualTo("NO_RELIABLE_EVIDENCE");
        assertThat(result.evidences()).isEmpty();
    }

    @Test
    void rerankerAndOneRecallFailureFailClosedWithoutEvidence() {
        Fixture fixture = new Fixture();
        fixture.keyword.failure = new IllegalStateException("es down");
        fixture.vector.result = List.of(candidate("vector", RecallSource.VECTOR));
        fixture.reranker = (query, values) -> { throw new RerankerUnavailableException("down"); };

        var result = fixture.service().retrieve(1L, 10567L, "request-6", "问题", List.of());

        assertThat(result.answerable()).isFalse();
        assertThat(result.degradationMode()).isEqualTo(DegradationMode.NO_RELIABLE_EVIDENCE);
        assertThat(result.resultCode()).isEqualTo("NO_RELIABLE_EVIDENCE");
        verify(fixture.validator).validate(eq(1L), eq(fixture.defaultScope), anyList());
    }

    @Test
    void bothRecallChannelsFailAsServiceUnavailable() {
        Fixture fixture = new Fixture();
        fixture.embeddingFailure = true;
        fixture.keyword.failure = new IllegalStateException("es down");

        assertThatThrownBy(() -> fixture.service().retrieve(1L, 10567L, "request-7", "问题", List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().code()).isEqualTo("KNOWLEDGE_SERVICE_UNAVAILABLE"));
    }

    @Test
    void adminDraftInspectionUsesDraftIndexesAndDraftPointerValidation() {
        Fixture fixture = new Fixture();
        fixture.keyword.result = List.of(candidate("draft", RecallSource.KEYWORD));
        fixture.vector.result = List.of(candidate("draft", RecallSource.VECTOR));
        fixture.reranker = (query, values) -> values.stream()
                .map(value -> value.withScore(0.9D)).toList();

        var result = fixture.service().retrieveAdmin(
                1L, 10567L, "request-8", "问题", List.of(), IndexLayer.DRAFT);

        assertThat(result.answerable()).isTrue();
        assertThat(fixture.keyword.lastLayer).isEqualTo(IndexLayer.DRAFT);
        assertThat(fixture.vector.lastLayer).isEqualTo(IndexLayer.DRAFT);
        verify(fixture.draftValidator).validate(eq(1L), anyList());
    }

    @Test
    void budgetExhaustionIsNotHiddenAsKeywordOnlyDegradation() {
        Fixture fixture = new Fixture();
        fixture.budgetExhausted = true;
        fixture.keyword.result = List.of(candidate("keyword", RecallSource.KEYWORD));

        assertThatThrownBy(() -> fixture.service().retrieve(
                1L, 10567L, "request-budget", "问题", List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().code())
                                .isEqualTo("KNOWLEDGE_MODEL_BUDGET_EXHAUSTED"));
    }

    @Test
    void stalePublishedCandidatesCannotMakeTheAnswerAnswerable() {
        Fixture fixture = new Fixture();
        fixture.vector.result = List.of(candidate("stale", RecallSource.VECTOR));
        fixture.keyword.result = List.of(candidate("stale", RecallSource.KEYWORD));
        fixture.reranker = (query, values) -> values.stream()
                .map(value -> value.withScore(0.9D)).toList();
        when(fixture.validator.validate(eq(1L), eq(fixture.defaultScope), anyList()))
                .thenReturn(new PublishedScopeValidation(false, List.of()));

        var result = fixture.service().retrieve(
                1L, 10567L, "request-stale", "退款规则", List.of());

        assertThat(result.answerable()).isFalse();
        assertThat(result.resultCode()).isEqualTo("NO_RELEVANT_EVIDENCE");
    }

    @Test
    void searchLogFailureCannotBreakAnOtherwiseValidAnswer() {
        Fixture fixture = new Fixture();
        fixture.vector.result = List.of(candidate("shared", RecallSource.VECTOR));
        fixture.keyword.result = List.of(candidate("shared", RecallSource.KEYWORD));
        fixture.reranker = (query, values) -> values.stream()
                .map(value -> value.withScore(0.9D)).toList();
        doThrow(new IllegalStateException("database unavailable"))
                .when(fixture.searchLogs).record(org.mockito.ArgumentMatchers.any());

        var result = fixture.service().retrieve(
                1L, 10567L, "request-log-failure", "退款规则", List.of());

        assertThat(result.answerable()).isTrue();
    }

    @Test
    void retriesOnceWithLatestReleaseScopeAndReusesEmbedding() {
        Fixture fixture = new Fixture();
        ActiveReleaseScope scope20 = scope(20L, 4L);
        ActiveReleaseScope scope21 = scope(21L, 5L);
        when(fixture.scopeLoader.load(1L, List.of(2L))).thenReturn(scope20, scope21);
        when(fixture.validator.validate(eq(1L), eq(scope20), anyList()))
                .thenReturn(new PublishedScopeValidation(true, List.of()));
        when(fixture.validator.validate(eq(1L), eq(scope21), anyList()))
                .thenAnswer(invocation -> new PublishedScopeValidation(false, invocation.getArgument(2)));
        fixture.vector.result = List.of(candidate("shared", RecallSource.VECTOR));
        fixture.keyword.result = List.of(candidate("shared", RecallSource.KEYWORD));
        fixture.reranker = (query, values) -> values.stream()
                .map(value -> value.withScore(0.9D)).toList();

        RetrievalResult result = fixture.service().retrieve(
                1L, 10567L, "request-switch", "问题", List.of(2L));

        assertThat(result.answerable()).isTrue();
        assertThat(fixture.keyword.scopes).containsExactly(scope20.versions(), scope21.versions());
        assertThat(fixture.vector.scopes).containsExactly(scope20.versions(), scope21.versions());
        assertThat(fixture.embeddingCalls).hasValue(1);
        verify(fixture.scopeLoader, times(2)).load(1L, List.of(2L));
        assertThat(fixture.registry.get("knowledge.retrieval.release.scope.versions")
                .summary().count()).isEqualTo(2);
        assertThat(fixture.registry.get("knowledge.retrieval.release.retry")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void failsClosedWhenReleaseChangesTwice() {
        Fixture fixture = new Fixture();
        ActiveReleaseScope scope20 = scope(20L, 4L);
        ActiveReleaseScope scope21 = scope(21L, 5L);
        when(fixture.scopeLoader.load(1L, List.of(2L))).thenReturn(scope20, scope21);
        when(fixture.validator.validate(eq(1L), any(ActiveReleaseScope.class), anyList()))
                .thenReturn(new PublishedScopeValidation(true, List.of()));
        fixture.vector.result = List.of(candidate("shared", RecallSource.VECTOR));
        fixture.keyword.result = List.of(candidate("shared", RecallSource.KEYWORD));
        fixture.reranker = (query, values) -> values.stream()
                .map(value -> value.withScore(0.9D)).toList();

        assertThatThrownBy(() -> fixture.service().retrieve(
                1L, 10567L, "request-double-switch", "问题", List.of(2L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode().code())
                                .isEqualTo("KNOWLEDGE_RELEASE_CHANGING"));
        assertThat(fixture.embeddingCalls).hasValue(1);
    }

    @Test
    void emptyPublishedScopeDoesNotCallModelsOrIndexes() {
        Fixture fixture = new Fixture();
        when(fixture.scopeLoader.load(1L, List.of(2L)))
                .thenReturn(new ActiveReleaseScope(Map.of(), List.of()));

        RetrievalResult result = fixture.service().retrieve(
                1L, 10567L, "request-empty-scope", "问题", List.of(2L));

        assertThat(result.answerable()).isFalse();
        assertThat(result.resultCode()).isEqualTo("NO_RELEVANT_EVIDENCE");
        assertThat(fixture.embeddingCalls).hasValue(0);
        assertThat(fixture.keyword.searchCalls).isZero();
        assertThat(fixture.vector.searchCalls).isZero();
        verify(fixture.validator, never()).validate(anyLong(), any(ActiveReleaseScope.class), anyList());
    }

    private static ActiveReleaseScope scope(long releaseId, long versionId) {
        return new ActiveReleaseScope(
                Map.of(2L, releaseId),
                List.of(new com.xjjk.knowledge.retrieval.model.DocumentVersionRef(
                        2L, 3L, versionId)));
    }

    private static RecallCandidate candidate(String id, RecallSource source) {
        return new RecallCandidate(new IndexChunk(
                id, 1L, 2L, 3L, 4L, 0, "退款规则", "售后", "正文", "hash", "{}"),
                0.9D, source);
    }

    private static final class Fixture {
        private final FakeKeywordIndex keyword = new FakeKeywordIndex();
        private final FakeVectorIndex vector = new FakeVectorIndex();
        private final PublishedVersionValidator validator = mock(PublishedVersionValidator.class);
        private final DraftVersionValidator draftValidator = mock(DraftVersionValidator.class);
        private final SearchLogRecorder searchLogs = mock(SearchLogRecorder.class);
        private final ActiveReleaseScopeLoader scopeLoader = mock(ActiveReleaseScopeLoader.class);
        private final ActiveReleaseScope defaultScope = scope(20L, 4L);
        private final AtomicInteger embeddingCalls = new AtomicInteger();
        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private boolean embeddingFailure;
        private boolean budgetExhausted;
        private Reranker reranker = (query, values) -> values;

        private Fixture() {
            when(scopeLoader.load(anyLong(), anyList())).thenReturn(defaultScope);
            when(validator.validate(eq(1L), any(ActiveReleaseScope.class), anyList()))
                    .thenAnswer(invocation -> new PublishedScopeValidation(false, invocation.getArgument(2)));
            when(draftValidator.validate(eq(1L), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        }

        private HybridRetrievalService service() {
            EmbeddingClient embeddings = new EmbeddingClient() {
                @Override public List<List<Float>> embedDocuments(List<String> documents) { throw new UnsupportedOperationException(); }
                @Override public List<Float> embedQuery(String query) {
                    embeddingCalls.incrementAndGet();
                    if (budgetExhausted) throw new CloudModelBudgetExceededException();
                    if (embeddingFailure) throw new EmbeddingUnavailableException("down");
                    return List.of(1F);
                }
            };
            RetrievalProperties properties = new RetrievalProperties();
            RerankerProperties rerankerProperties = new RerankerProperties();
            return new HybridRetrievalService(
                    embeddings, keyword, vector, new RrfFusion(), reranker, rerankerProperties,
                    properties, scopeLoader, validator, draftValidator, searchLogs,
                    new KnowledgeMetrics(registry));
        }
    }

    private static final class FakeKeywordIndex implements KeywordIndex {
        private List<RecallCandidate> result = List.of();
        private RuntimeException failure;
        private int lastTopK;
        private IndexLayer lastLayer;
        private int searchCalls;
        private final java.util.ArrayList<List<com.xjjk.knowledge.retrieval.model.DocumentVersionRef>> scopes =
                new java.util.ArrayList<>();
        @Override public void ensureReady() {}
        @Override public void replaceVersion(IndexLayer layer, List<IndexChunk> chunks) {}
        @Override public List<RecallCandidate> search(IndexLayer layer, long tenantId, List<Long> ids, String query, int topK) {
            return search(layer, tenantId, ids, List.of(), query, topK);
        }
        @Override public List<RecallCandidate> search(
                IndexLayer layer, long tenantId, List<Long> ids,
                List<com.xjjk.knowledge.retrieval.model.DocumentVersionRef> allowedVersions,
                String query, int topK) {
            lastLayer = layer;
            lastTopK = topK;
            searchCalls++;
            scopes.add(List.copyOf(allowedVersions));
            if (failure != null) throw failure;
            return result;
        }
        @Override public IndexVerification verifyVersion(IndexLayer layer, long tenantId, long documentId, long versionId) { return new IndexVerification(java.util.Map.of()); }
        @Override public void deleteVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {}
    }

    private static final class FakeVectorIndex implements VectorIndex {
        private List<RecallCandidate> result = List.of();
        private RuntimeException failure;
        private int lastTopK;
        private IndexLayer lastLayer;
        private int searchCalls;
        private final java.util.ArrayList<List<com.xjjk.knowledge.retrieval.model.DocumentVersionRef>> scopes =
                new java.util.ArrayList<>();
        @Override public void ensureReady() {}
        @Override public void replaceVersion(IndexLayer layer, List<IndexChunk> chunks, List<List<Float>> vectors) {}
        @Override public List<RecallCandidate> search(IndexLayer layer, long tenantId, List<Long> ids, List<Float> vector, int topK) {
            return search(layer, tenantId, ids, List.of(), vector, topK);
        }
        @Override public List<RecallCandidate> search(
                IndexLayer layer, long tenantId, List<Long> ids,
                List<com.xjjk.knowledge.retrieval.model.DocumentVersionRef> allowedVersions,
                List<Float> vector, int topK) {
            lastLayer = layer;
            lastTopK = topK;
            searchCalls++;
            scopes.add(List.copyOf(allowedVersions));
            if (failure != null) throw failure;
            return result;
        }
        @Override public IndexVerification verifyVersion(IndexLayer layer, long tenantId, long documentId, long versionId) { return new IndexVerification(java.util.Map.of()); }
        @Override public void deleteVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {}
    }
}

package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.common.error.BusinessException;
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
import com.xjjk.knowledge.retrieval.rerank.Reranker;
import com.xjjk.knowledge.retrieval.rerank.RerankerProperties;
import com.xjjk.knowledge.retrieval.rerank.RerankerUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        verify(fixture.validator).validate(eq(1L), anyList());
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
        verify(fixture.validator).validate(eq(1L), anyList());
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
    void rerankerFailureKeepsOnlyStrictDualRecallCandidates() {
        Fixture fixture = new Fixture();
        fixture.vector.result = List.of(candidate("shared", RecallSource.VECTOR), candidate("vector", RecallSource.VECTOR));
        fixture.keyword.result = List.of(candidate("shared", RecallSource.KEYWORD), candidate("keyword", RecallSource.KEYWORD));
        fixture.reranker = (query, values) -> { throw new RerankerUnavailableException("down"); };

        var result = fixture.service().retrieve(1L, 10567L, "request-5", "问题", List.of());

        assertThat(result.answerable()).isTrue();
        assertThat(result.degradationMode()).isEqualTo(DegradationMode.RERANKER_STRICT_RRF);
        assertThat(result.evidences()).extracting(value -> value.chunk().chunkId()).containsExactly("shared");
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
        verify(fixture.validator).validate(eq(1L), anyList());
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

    private static RecallCandidate candidate(String id, RecallSource source) {
        return new RecallCandidate(new IndexChunk(
                id, 1L, 2L, 3L, 4L, 0, "退款规则", "售后", "正文", "hash", "{}"),
                0.9D, source);
    }

    private static final class Fixture {
        private final FakeKeywordIndex keyword = new FakeKeywordIndex();
        private final FakeVectorIndex vector = new FakeVectorIndex();
        private final PublishedVersionValidator validator = mock(PublishedVersionValidator.class);
        private final SearchLogRecorder searchLogs = mock(SearchLogRecorder.class);
        private boolean embeddingFailure;
        private Reranker reranker = (query, values) -> values;

        private Fixture() {
            when(validator.validate(eq(1L), anyList())).thenAnswer(invocation -> invocation.getArgument(1));
        }

        private HybridRetrievalService service() {
            EmbeddingClient embeddings = new EmbeddingClient() {
                @Override public List<List<Float>> embedDocuments(List<String> documents) { throw new UnsupportedOperationException(); }
                @Override public List<Float> embedQuery(String query) {
                    if (embeddingFailure) throw new EmbeddingUnavailableException("down");
                    return List.of(1F);
                }
            };
            RetrievalProperties properties = new RetrievalProperties();
            RerankerProperties rerankerProperties = new RerankerProperties();
            return new HybridRetrievalService(
                    embeddings, keyword, vector, new RrfFusion(), reranker, rerankerProperties,
                    properties, validator, searchLogs);
        }
    }

    private static final class FakeKeywordIndex implements KeywordIndex {
        private List<RecallCandidate> result = List.of();
        private RuntimeException failure;
        private int lastTopK;
        @Override public void ensureReady() {}
        @Override public void replaceVersion(IndexLayer layer, List<IndexChunk> chunks) {}
        @Override public List<RecallCandidate> search(IndexLayer layer, long tenantId, List<Long> ids, String query, int topK) {
            lastTopK = topK;
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
        @Override public void ensureReady() {}
        @Override public void replaceVersion(IndexLayer layer, List<IndexChunk> chunks, List<List<Float>> vectors) {}
        @Override public List<RecallCandidate> search(IndexLayer layer, long tenantId, List<Long> ids, List<Float> vector, int topK) {
            lastTopK = topK;
            if (failure != null) throw failure;
            return result;
        }
        @Override public IndexVerification verifyVersion(IndexLayer layer, long tenantId, long documentId, long versionId) { return new IndexVerification(java.util.Map.of()); }
        @Override public void deleteVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {}
    }
}

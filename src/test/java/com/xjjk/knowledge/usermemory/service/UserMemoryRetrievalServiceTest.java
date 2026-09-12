package com.xjjk.knowledge.usermemory.service;

import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.retrieval.index.SearchIndexUnavailableException;
import com.xjjk.knowledge.retrieval.rerank.Reranker;
import com.xjjk.knowledge.usermemory.config.UserMemoryIndexProperties;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;
import com.xjjk.knowledge.usermemory.index.MemoryKeywordIndex;
import com.xjjk.knowledge.usermemory.index.MemorySearchHit;
import com.xjjk.knowledge.usermemory.index.MemoryVectorIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemoryRetrievalServiceTest {

    @Test
    void fusesTwoChannelsAndReturnsNoContent() {
        Fixture fixture = new Fixture();
        MemoryIndexDocument document = fixture.document("m-1", 2);
        when(fixture.embeddings.embedQuery("我使用什么编程语言"))
                .thenReturn(fixture.vector);
        when(fixture.keyword.search(1, 74680, 3, "我使用什么编程语言", 20))
                .thenReturn(List.of(new MemorySearchHit(document, 4.2, "KEYWORD")));
        when(fixture.vectorIndex.search(1, 74680, 3, fixture.vector, 20))
                .thenReturn(List.of(new MemorySearchHit(document, 0.91, "VECTOR")));

        var result = fixture.service().retrieve(
                1, 74680, 3, "我使用什么编程语言");

        assertThat(result.degradationMode()).isEqualTo("NONE");
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.memoryId()).isEqualTo("m-1");
            assertThat(candidate.memoryVersion()).isEqualTo(2);
            assertThat(candidate.sources()).containsExactlyInAnyOrder("KEYWORD", "VECTOR");
        });
        assertThat(result.toString()).doesNotContain("Java 开发");
    }

    @Test
    void degradesToKeywordAndReturnsEmptyWhenBothChannelsFail() {
        Fixture fixture = new Fixture();
        MemoryIndexDocument document = fixture.document("m-1", 2);
        when(fixture.embeddings.embedQuery("语言"))
                .thenThrow(new SearchIndexUnavailableException("embedding down"));
        when(fixture.keyword.search(1, 74680, 3, "语言", 20))
                .thenReturn(List.of(new MemorySearchHit(document, 3.1, "KEYWORD")));

        var keywordOnly = fixture.service().retrieve(1, 74680, 3, "语言");
        assertThat(keywordOnly.degradationMode()).isEqualTo("KEYWORD_ONLY");
        assertThat(keywordOnly.candidates()).hasSize(1);

        when(fixture.keyword.search(1, 74680, 3, "都坏了", 20))
                .thenThrow(new SearchIndexUnavailableException("es down"));
        when(fixture.embeddings.embedQuery("都坏了"))
                .thenThrow(new SearchIndexUnavailableException("embedding down"));
        var unavailable = fixture.service().retrieve(1, 74680, 3, "都坏了");
        assertThat(unavailable.degradationMode()).isEqualTo("ALL_RECALL_UNAVAILABLE");
        assertThat(unavailable.candidates()).isEmpty();
    }

    private static final class Fixture {
        private final MemoryKeywordIndex keyword = mock(MemoryKeywordIndex.class);
        private final MemoryVectorIndex vectorIndex = mock(MemoryVectorIndex.class);
        private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
        private final Reranker reranker = mock(Reranker.class);
        private final UserMemoryIndexProperties properties = new UserMemoryIndexProperties();
        private final List<Float> vector = java.util.Collections.nCopies(2560, 0.01F);

        private Fixture() {
            properties.setEnabled(true);
            properties.getRetrieval().setRerankEnabled(false);
        }

        private UserMemoryRetrievalService service() {
            return new UserMemoryRetrievalService(
                    properties, keyword, vectorIndex, embeddings, reranker, Runnable::run);
        }

        private MemoryIndexDocument document(String id, long version) {
            return new MemoryIndexDocument(
                    id, 1, 74680, 3, version,
                    "AUTO_EXTRACT", "WORK_COMMON_SCOPE", "work.common_scope",
                    "用户常用工作范围是 Java 开发", 0.95, null);
        }
    }
}

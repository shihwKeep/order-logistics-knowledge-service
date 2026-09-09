package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingProperties;
import com.xjjk.knowledge.retrieval.index.IndexVerification;
import com.xjjk.knowledge.retrieval.index.KeywordIndex;
import com.xjjk.knowledge.retrieval.index.VectorIndex;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.RecallCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftIndexingServiceTest {

    @Test
    void embedsDualWritesVerifiesAndMarksVersionReady() {
        DocumentVersion version = version(2);
        List<IndexChunk> chunks = List.of(chunk(0, "hash-a"), chunk(1, "hash-b"));
        FakeChunkIndexRepository repository = new FakeChunkIndexRepository(chunks);
        FakeKeywordIndex keyword = new FakeKeywordIndex();
        FakeVectorIndex vector = new FakeVectorIndex();
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setModel("qwen3-embedding:4b-q4_K_M");
        properties.setDimension(4);
        properties.setInstructionVersion("instruction-v1");
        EmbeddingClient embeddings = new EmbeddingClient() {
            @Override public List<List<Float>> embedDocuments(List<String> documents) {
                assertThat(documents).containsExactly("正文-0", "正文-1");
                return List.of(List.of(1F, 0F, 0F, 0F), List.of(0F, 1F, 0F, 0F));
            }
            @Override public List<Float> embedQuery(String query) { throw new UnsupportedOperationException(); }
        };
        DraftIndexingService service = new DraftIndexingService(
                repository, embeddings, properties, keyword, vector);

        service.index(version);

        assertThat(keyword.layer).isEqualTo(IndexLayer.DRAFT);
        assertThat(vector.layer).isEqualTo(IndexLayer.DRAFT);
        assertThat(vector.vectors).hasSize(2);
        assertThat(repository.ready).isNotNull();
        assertThat(repository.ready.model()).isEqualTo("qwen3-embedding:4b-q4_K_M");
        assertThat(repository.ready.dimension()).isEqualTo(4);
        assertThat(repository.ready.instructionVersion()).isEqualTo("instruction-v1");
        assertThat(repository.ready.manifestSha256()).hasSize(64);
    }

    @Test
    void refusesReadyWhenPersistedChunkCountDoesNotMatchVersion() {
        DocumentVersion version = version(2);
        FakeChunkIndexRepository repository = new FakeChunkIndexRepository(List.of(chunk(0, "hash-a")));
        EmbeddingClient embeddings = new EmbeddingClient() {
            @Override public List<List<Float>> embedDocuments(List<String> documents) { return List.of(List.of(1F)); }
            @Override public List<Float> embedQuery(String query) { throw new UnsupportedOperationException(); }
        };
        DraftIndexingService service = new DraftIndexingService(
                repository, embeddings, new EmbeddingProperties(), new FakeKeywordIndex(), new FakeVectorIndex());

        assertThatThrownBy(() -> service.index(version))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chunk 数量");
        assertThat(repository.ready).isNull();
    }

    private static DocumentVersion version(int chunkCount) {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                4L, 1L, 2L, 3L, 1, DocumentStatus.INDEXING,
                "refund.txt", "txt", "text/plain", 12L, "sha", "source-key",
                null, "text-v1", "structural-v1", null, null, null, null, null,
                false, 0, 1, chunkCount, null, null, null, 10567L, now, now);
    }

    private static IndexChunk chunk(int index, String sha256) {
        return new IndexChunk("1-3-4-" + index, 1L, 2L, 3L, 4L, index,
                "退款规则", "售后", "正文-" + index, sha256, "{}");
    }

    private static final class FakeChunkIndexRepository implements ChunkIndexRepository {
        private final List<IndexChunk> chunks;
        private ReadyIndexMetadata ready;
        private FakeChunkIndexRepository(List<IndexChunk> chunks) { this.chunks = chunks; }
        @Override public List<IndexChunk> loadVersionChunks(DocumentVersion version) { return chunks; }
        @Override public void markReady(DocumentVersion version, ReadyIndexMetadata metadata) { ready = metadata; }
    }

    private static final class FakeKeywordIndex implements KeywordIndex {
        private IndexLayer layer;
        private final Map<String, String> fingerprints = new LinkedHashMap<>();
        @Override public void ensureReady() {}
        @Override public void replaceVersion(IndexLayer layer, List<IndexChunk> chunks) {
            this.layer = layer;
            chunks.forEach(chunk -> fingerprints.put(chunk.chunkId(), chunk.contentSha256()));
        }
        @Override public List<RecallCandidate> search(IndexLayer layer, long tenantId, List<Long> ids, String query, int topK) { return List.of(); }
        @Override public IndexVerification verifyVersion(IndexLayer layer, long tenantId, long documentId, long versionId) { return new IndexVerification(fingerprints); }
        @Override public void deleteVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {}
    }

    private static final class FakeVectorIndex implements VectorIndex {
        private IndexLayer layer;
        private List<List<Float>> vectors;
        private final Map<String, String> fingerprints = new LinkedHashMap<>();
        @Override public void ensureReady() {}
        @Override public void replaceVersion(IndexLayer layer, List<IndexChunk> chunks, List<List<Float>> vectors) {
            this.layer = layer;
            this.vectors = vectors;
            chunks.forEach(chunk -> fingerprints.put(chunk.chunkId(), chunk.contentSha256()));
        }
        @Override public List<RecallCandidate> search(IndexLayer layer, long tenantId, List<Long> ids, List<Float> vector, int topK) { return List.of(); }
        @Override public IndexVerification verifyVersion(IndexLayer layer, long tenantId, long documentId, long versionId) { return new IndexVerification(fingerprints); }
        @Override public void deleteVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {}
    }
}

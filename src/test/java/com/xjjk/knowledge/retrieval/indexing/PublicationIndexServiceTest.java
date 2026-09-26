package com.xjjk.knowledge.retrieval.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingProperties;
import com.xjjk.knowledge.retrieval.index.IndexVerification;
import com.xjjk.knowledge.retrieval.index.KeywordIndex;
import com.xjjk.knowledge.retrieval.index.VectorIndex;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicationIndexServiceTest {
    @Test
    void reportsReadyOnlyWhenMysqlEsAndMilvusFingerprintsMatch() {
        List<IndexChunk> chunks = List.of(
                chunk(0, "hash-a"), chunk(1, "hash-b"));
        DocumentVersion version = version(chunks);
        ChunkIndexRepository repository = mock(ChunkIndexRepository.class);
        KeywordIndex keyword = mock(KeywordIndex.class);
        VectorIndex vector = mock(VectorIndex.class);
        when(repository.loadVersionChunks(version)).thenReturn(chunks);
        Map<String, String> expected = IndexManifest.fingerprints(chunks);
        when(keyword.verifyVersion(IndexLayer.PUBLISHED, 1L, 3L, 4L))
                .thenReturn(new IndexVerification(expected));
        when(vector.verifyVersion(IndexLayer.PUBLISHED, 1L, 3L, 4L))
                .thenReturn(new IndexVerification(expected));
        PublicationIndexService service = service(repository, keyword, vector);

        assertThat(service.isPublishedReady(version)).isTrue();

        when(vector.verifyVersion(IndexLayer.PUBLISHED, 1L, 3L, 4L))
                .thenReturn(new IndexVerification(Map.of()));
        assertThat(service.isPublishedReady(version)).isFalse();
    }

    private PublicationIndexService service(
            ChunkIndexRepository repository, KeywordIndex keyword, VectorIndex vector) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setModel("qwen3.7-text-embedding");
        properties.setDimension(4);
        properties.setInstructionVersion("instruction-v1");
        return new PublicationIndexService(
                repository, mock(EmbeddingClient.class), properties, keyword, vector);
    }

    private DocumentVersion version(List<IndexChunk> chunks) {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                4L, 1L, 2L, 3L, 1, DocumentStatus.ARCHIVED,
                "refund.txt", "txt", "text/plain", 12L, "sha", "source-key",
                null, "text-v1", "structural-v1", "qwen3.7-text-embedding", 4,
                "instruction-v1", IndexManifest.sha256(chunks), now,
                false, 0, 1, chunks.size(), null, null, null, 10567L, now, now);
    }

    private IndexChunk chunk(int index, String sha256) {
        return new IndexChunk(
                "1-3-4-" + index, 1L, 2L, 3L, 4L, index,
                "退款规则", "售后", "正文-" + index, sha256, "{}");
    }
}

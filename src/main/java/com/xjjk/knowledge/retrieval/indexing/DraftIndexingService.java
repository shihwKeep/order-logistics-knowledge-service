package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingProperties;
import com.xjjk.knowledge.retrieval.index.KeywordIndex;
import com.xjjk.knowledge.retrieval.index.VectorIndex;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 草稿索引编排器。只有 MySQL Chunk、ES 草稿索引和 Milvus 草稿集合完全一致时，
 * 才把版本推进为 READY；任何一步失败均由外层任务租约进入重试。
 */
@Service
public class DraftIndexingService {
    private final ChunkIndexRepository repository;
    private final EmbeddingClient embeddings;
    private final EmbeddingProperties embeddingProperties;
    private final KeywordIndex keywordIndex;
    private final VectorIndex vectorIndex;

    public DraftIndexingService(
            ChunkIndexRepository repository,
            EmbeddingClient embeddings,
            EmbeddingProperties embeddingProperties,
            KeywordIndex keywordIndex,
            VectorIndex vectorIndex) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.embeddingProperties = embeddingProperties;
        this.keywordIndex = keywordIndex;
        this.vectorIndex = vectorIndex;
    }

    public void index(DocumentVersion version) {
        List<IndexChunk> chunks = repository.loadVersionChunks(version);
        if (chunks.isEmpty() || chunks.size() != version.chunkCount()) {
            throw new IllegalStateException(
                    "MySQL Chunk 数量与版本记录不一致: expected=" + version.chunkCount()
                            + ", actual=" + chunks.size());
        }
        List<List<Float>> vectors = embeddings.embedDocuments(
                chunks.stream().map(IndexChunk::content).toList());
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException("Embedding 返回数量与 Chunk 数量不一致");
        }

        keywordIndex.ensureReady();
        vectorIndex.ensureReady();
        keywordIndex.replaceVersion(IndexLayer.DRAFT, chunks);
        vectorIndex.replaceVersion(IndexLayer.DRAFT, chunks, vectors);

        Map<String, String> expected = IndexManifest.fingerprints(chunks);
        Map<String, String> keywordActual = keywordIndex.verifyVersion(
                IndexLayer.DRAFT, version.tenantId(), version.documentId(), version.id()).fingerprints();
        Map<String, String> vectorActual = vectorIndex.verifyVersion(
                IndexLayer.DRAFT, version.tenantId(), version.documentId(), version.id()).fingerprints();
        if (!expected.equals(keywordActual) || !expected.equals(vectorActual)) {
            throw new IllegalStateException("ES/Milvus 索引校验未通过，拒绝标记 READY");
        }

        repository.markReady(version, new ReadyIndexMetadata(
                embeddingProperties.getModel(), embeddingProperties.getDimension(),
                embeddingProperties.getInstructionVersion(), IndexManifest.sha256(chunks)));
    }
}

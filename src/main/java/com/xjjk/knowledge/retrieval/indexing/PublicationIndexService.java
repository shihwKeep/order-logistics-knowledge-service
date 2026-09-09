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

/** 发布前构造并校验线上双索引；本类不改 MySQL 发布指针。 */
@Service
public class PublicationIndexService {
    private final ChunkIndexRepository chunks;
    private final EmbeddingClient embeddings;
    private final EmbeddingProperties embeddingProperties;
    private final KeywordIndex keywordIndex;
    private final VectorIndex vectorIndex;

    public PublicationIndexService(
            ChunkIndexRepository chunks,
            EmbeddingClient embeddings,
            EmbeddingProperties embeddingProperties,
            KeywordIndex keywordIndex,
            VectorIndex vectorIndex) {
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.embeddingProperties = embeddingProperties;
        this.keywordIndex = keywordIndex;
        this.vectorIndex = vectorIndex;
    }

    public void preparePublished(DocumentVersion version) {
        validateEmbeddingContract(version);
        List<IndexChunk> values = chunks.loadVersionChunks(version);
        if (values.isEmpty() || values.size() != version.chunkCount()) {
            throw new IllegalStateException("发布版本的 MySQL Chunk 数量不一致");
        }
        String manifest = IndexManifest.sha256(values);
        if (!manifest.equals(version.indexManifestSha256())) {
            throw new IllegalStateException("发布版本内容清单已经变化，必须重新生成草稿索引");
        }
        List<List<Float>> vectors = embeddings.embedDocuments(
                values.stream().map(IndexChunk::content).toList());
        keywordIndex.ensureReady();
        vectorIndex.ensureReady();
        keywordIndex.replaceVersion(IndexLayer.PUBLISHED, values);
        vectorIndex.replaceVersion(IndexLayer.PUBLISHED, values, vectors);

        Map<String, String> expected = IndexManifest.fingerprints(values);
        Map<String, String> keywordActual = keywordIndex.verifyVersion(
                IndexLayer.PUBLISHED, version.tenantId(), version.documentId(), version.id()).fingerprints();
        Map<String, String> vectorActual = vectorIndex.verifyVersion(
                IndexLayer.PUBLISHED, version.tenantId(), version.documentId(), version.id()).fingerprints();
        if (!expected.equals(keywordActual) || !expected.equals(vectorActual)) {
            throw new IllegalStateException("发布层 ES/Milvus 索引校验未通过");
        }
    }

    public void deletePublished(long tenantId, long documentId, long versionId) {
        keywordIndex.deleteVersion(IndexLayer.PUBLISHED, tenantId, documentId, versionId);
        vectorIndex.deleteVersion(IndexLayer.PUBLISHED, tenantId, documentId, versionId);
    }

    private void validateEmbeddingContract(DocumentVersion version) {
        if (!embeddingProperties.getModel().equals(version.embeddingModel())
                || version.embeddingDimension() == null
                || version.embeddingDimension() != embeddingProperties.getDimension()
                || !embeddingProperties.getInstructionVersion().equals(version.embeddingInstructionVersion())) {
            throw new IllegalStateException("发布版本的 Embedding 契约与当前线上配置不一致");
        }
    }
}

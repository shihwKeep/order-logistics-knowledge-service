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

/** 构造并校验指定索引层；本类只重建派生索引，不改 MySQL 版本状态或发布指针。 */
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
        prepare(version, IndexLayer.PUBLISHED, true);
    }

    /**
     * 回滚历史 Release 前先比对 MySQL Chunk 指纹与 ES/Milvus 正式层。
     * Index/Collection 不存在时先创建空结构，随后返回 false 触发幂等重建。
     */
    public boolean isPublishedReady(DocumentVersion version) {
        List<IndexChunk> values = loadValidatedChunks(version, true);
        keywordIndex.ensureReady();
        vectorIndex.ensureReady();
        Map<String, String> expected = IndexManifest.fingerprints(values);
        Map<String, String> keywordActual = keywordIndex.verifyVersion(
                IndexLayer.PUBLISHED, version.tenantId(), version.documentId(), version.id())
                .fingerprints();
        Map<String, String> vectorActual = vectorIndex.verifyVersion(
                IndexLayer.PUBLISHED, version.tenantId(), version.documentId(), version.id())
                .fingerprints();
        return expected.equals(keywordActual) && expected.equals(vectorActual);
    }

    /** 灾备恢复时依据 MySQL Chunk 重建当前草稿层，不改变草稿状态。 */
    public void prepareDraft(DocumentVersion version) {
        prepare(version, IndexLayer.DRAFT, true);
    }

    /** 维护迁移专用：使用当前模型重建，旧契约仅在所有外部索引校验通过后更新。 */
    public void recoverPublished(DocumentVersion version) {
        prepare(version, IndexLayer.PUBLISHED, false);
    }

    /** 维护迁移专用：使用当前模型重建，旧契约仅在所有外部索引校验通过后更新。 */
    public void recoverDraft(DocumentVersion version) {
        prepare(version, IndexLayer.DRAFT, false);
    }

    private void prepare(DocumentVersion version, IndexLayer layer, boolean requireCurrentContract) {
        List<IndexChunk> values = loadValidatedChunks(version, requireCurrentContract);
        List<List<Float>> vectors = embeddings.embedDocuments(
                values.stream().map(IndexChunk::content).toList());
        keywordIndex.ensureReady();
        vectorIndex.ensureReady();
        keywordIndex.replaceVersion(layer, values);
        vectorIndex.replaceVersion(layer, values, vectors);

        Map<String, String> expected = IndexManifest.fingerprints(values);
        Map<String, String> keywordActual = keywordIndex.verifyVersion(
                layer, version.tenantId(), version.documentId(), version.id()).fingerprints();
        Map<String, String> vectorActual = vectorIndex.verifyVersion(
                layer, version.tenantId(), version.documentId(), version.id()).fingerprints();
        if (!expected.equals(keywordActual) || !expected.equals(vectorActual)) {
            throw new IllegalStateException(layer + " 层 ES/Milvus 索引校验未通过");
        }
    }

    private List<IndexChunk> loadValidatedChunks(
            DocumentVersion version, boolean requireCurrentContract) {
        if (requireCurrentContract) {
            validateEmbeddingContract(version);
        }
        List<IndexChunk> values = chunks.loadVersionChunks(version);
        if (values.isEmpty() || values.size() != version.chunkCount()) {
            throw new IllegalStateException("待恢复版本的 MySQL Chunk 数量不一致");
        }
        String manifest = IndexManifest.sha256(values);
        if (!manifest.equals(version.indexManifestSha256())) {
            throw new IllegalStateException("发布版本内容清单已经变化，必须重新生成草稿索引");
        }
        return values;
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

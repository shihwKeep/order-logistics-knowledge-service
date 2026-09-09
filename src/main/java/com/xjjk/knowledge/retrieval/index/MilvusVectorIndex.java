package com.xjjk.knowledge.retrieval.index;

import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.RecallCandidate;
import com.xjjk.knowledge.retrieval.model.RecallSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 2560 维 Milvus 向量索引；Collection 维度漂移时立即拒绝使用。 */
@Component
public class MilvusVectorIndex implements VectorIndex {
    private final MilvusGateway gateway;
    private final MilvusProperties properties;

    public MilvusVectorIndex(MilvusGateway gateway, MilvusProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    @Override
    public void ensureReady() {
        ensure(properties.getDraftCollection());
        ensure(properties.getPublishedCollection());
    }

    @Override
    public void replaceVersion(IndexLayer layer, List<IndexChunk> chunks, List<List<Float>> vectors) {
        if (chunks == null || vectors == null || chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("Chunk 与向量数量不一致");
        }
        if (chunks.isEmpty()) {
            return;
        }
        for (List<Float> vector : vectors) {
            validateVector(vector);
        }
        IndexChunk first = chunks.getFirst();
        deleteVersion(layer, first.tenantId(), first.documentId(), first.versionId());
        List<MilvusVectorRow> rows = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            rows.add(new MilvusVectorRow(chunks.get(index), vectors.get(index)));
        }
        long upserted = gateway.upsert(properties.collectionName(layer), rows);
        if (upserted != rows.size()) {
            throw new SearchIndexUnavailableException("Milvus 确认写入数量不一致");
        }
    }

    @Override
    public List<RecallCandidate> search(
            IndexLayer layer, long tenantId, List<Long> knowledgeBaseIds, List<Float> vector, int topK) {
        validateVector(vector);
        StringBuilder filter = new StringBuilder("tenant_id == ").append(tenantId);
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            filter.append(" && knowledge_base_id in [")
                    .append(String.join(",", knowledgeBaseIds.stream().map(String::valueOf).toList()))
                    .append(']');
        }
        return gateway.search(properties.collectionName(layer), filter.toString(), vector, topK).stream()
                .map(match -> new RecallCandidate(match.chunk(), match.score(), RecallSource.VECTOR))
                .toList();
    }

    @Override
    public IndexVerification verifyVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {
        return new IndexVerification(gateway.fingerprints(properties.collectionName(layer),
                versionFilter(tenantId, documentId, versionId), properties.getVerificationLimit()));
    }

    @Override
    public void deleteVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {
        gateway.delete(properties.collectionName(layer), versionFilter(tenantId, documentId, versionId));
    }

    private void ensure(String collection) {
        MilvusCollectionSpec existing = gateway.describe(collection);
        if (existing == null) {
            gateway.create(new MilvusCollectionSpec(collection, properties.getDimension(), "COSINE", "chunk_id"));
        } else {
            if (existing.dimension() != properties.getDimension()) {
                throw new IllegalStateException("Milvus Collection 向量维度与配置不一致");
            }
            if (!"COSINE".equals(existing.metric()) || !"chunk_id".equals(existing.primaryKeyField())) {
                throw new IllegalStateException("Milvus Collection Schema 与配置不一致");
            }
        }
        gateway.load(collection);
    }

    private void validateVector(List<Float> vector) {
        if (vector == null || vector.size() != properties.getDimension()
                || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
            throw new IllegalArgumentException("Milvus 向量维度或数值不合法");
        }
    }

    private String versionFilter(long tenantId, long documentId, long versionId) {
        return "tenant_id == " + tenantId + " && document_id == " + documentId + " && version_id == " + versionId;
    }
}

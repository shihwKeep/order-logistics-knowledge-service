package com.xjjk.knowledge.usermemory.index;

import com.xjjk.knowledge.retrieval.index.SearchIndexUnavailableException;
import com.xjjk.knowledge.usermemory.config.UserMemoryIndexProperties;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/** 用户记忆专用 Milvus 领域适配器，负责 schema 与强制范围表达式。 */
@Component
public class MilvusMemoryVectorIndex implements MemoryVectorIndex {
    private final MemoryMilvusGateway gateway;
    private final UserMemoryIndexProperties properties;
    private final Clock clock;

    @Autowired
    public MilvusMemoryVectorIndex(
            MemoryMilvusGateway gateway,
            UserMemoryIndexProperties properties) {
        this(gateway, properties, Clock.systemUTC());
    }

    MilvusMemoryVectorIndex(
            MemoryMilvusGateway gateway,
            UserMemoryIndexProperties properties,
            Clock clock) {
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void ensureReady() {
        String collection = collection();
        MemoryMilvusSpec existing = gateway.describe(collection);
        if (existing == null) {
            gateway.create(new MemoryMilvusSpec(
                    collection, properties.getMilvus().getDimension(),
                    "COSINE", "memory_id"));
        } else if (existing.dimension() != properties.getMilvus().getDimension()
                || !"COSINE".equals(existing.metric())
                || !"memory_id".equals(existing.primaryKeyField())) {
            throw new IllegalStateException("用户记忆 Milvus Schema 与配置不一致");
        }
        gateway.load(collection);
    }

    @Override
    public void upsert(MemoryIndexDocument document, List<Float> vector) {
        validateVector(vector);
        long count = gateway.upsert(collection(), new MemoryVectorRow(document, vector));
        if (count != 1L) {
            throw new SearchIndexUnavailableException("Milvus 用户记忆写入数量不一致");
        }
    }

    @Override
    public void delete(long tenantId, long userId, long generation, String memoryId) {
        gateway.delete(collection(), ownerFilter(tenantId, userId, generation)
                + " && memory_id == \"" + escape(memoryId) + "\"");
    }

    @Override
    public void deleteExplicitScope(long tenantId, long userId, long generation) {
        gateway.delete(collection(), ownerFilter(tenantId, userId, generation)
                + " && source_type == \"USER_EXPLICIT\"");
    }

    @Override
    public void clearGeneration(long tenantId, long userId, long generation) {
        gateway.delete(collection(), ownerFilter(tenantId, userId, generation));
    }

    @Override
    public List<MemorySearchHit> search(
            long tenantId,
            long userId,
            long generation,
            List<Float> vector,
            int topK) {
        validateVector(vector);
        if (topK <= 0) {
            throw new IllegalArgumentException("Milvus 用户记忆 topK 必须大于零");
        }
        long now = clock.millis();
        String filter = ownerFilter(tenantId, userId, generation)
                + " && (expires_at == 0 || expires_at > " + now + ")";
        return gateway.search(collection(), filter, vector, topK);
    }

    private String ownerFilter(long tenantId, long userId, long generation) {
        if (tenantId <= 0 || userId <= 0 || generation <= 0) {
            throw new IllegalArgumentException("Milvus 用户记忆范围不合法");
        }
        return "tenant_id == " + tenantId + " && user_id == " + userId
                + " && memory_generation == " + generation;
    }

    private void validateVector(List<Float> vector) {
        if (vector == null || vector.size() != properties.getMilvus().getDimension()
                || vector.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
            throw new IllegalArgumentException("Milvus 用户记忆向量维度或数值不合法");
        }
    }

    private String collection() {
        return properties.getMilvus().getCollection();
    }

    private String escape(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("memoryId 不能为空");
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

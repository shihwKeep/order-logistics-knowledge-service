package com.xjjk.knowledge.usermemory.service;

import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.observation.KnowledgeMetrics;
import com.xjjk.knowledge.usermemory.config.UserMemoryIndexProperties;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexOperation;
import com.xjjk.knowledge.usermemory.index.MemoryKeywordIndex;
import com.xjjk.knowledge.usermemory.index.MemoryVectorIndex;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/** 把一个幂等记忆事件同步到两个独立索引；任一路失败由 Agent Outbox 重试。 */
@Service
public class UserMemoryIndexService {
    private final UserMemoryIndexProperties properties;
    private final MemoryKeywordIndex keywordIndex;
    private final MemoryVectorIndex vectorIndex;
    private final EmbeddingClient embeddings;
    private final KnowledgeMetrics metrics;
    private volatile boolean ready;

    @Autowired
    public UserMemoryIndexService(
            UserMemoryIndexProperties properties,
            MemoryKeywordIndex keywordIndex,
            MemoryVectorIndex vectorIndex,
            EmbeddingClient embeddings,
            KnowledgeMetrics metrics) {
        this.properties = properties;
        this.keywordIndex = keywordIndex;
        this.vectorIndex = vectorIndex;
        this.embeddings = embeddings;
        this.metrics = metrics;
    }

    UserMemoryIndexService(
            UserMemoryIndexProperties properties,
            MemoryKeywordIndex keywordIndex,
            MemoryVectorIndex vectorIndex,
            EmbeddingClient embeddings) {
        this(properties, keywordIndex, vectorIndex, embeddings, null);
    }

    public void apply(MemoryIndexOperation operation, MemoryIndexDocument document) {
        observed("UPSERT", () -> {
            requireEnabled();
            if (operation != MemoryIndexOperation.UPSERT || document == null) {
                throw new IllegalArgumentException("UPSERT 必须携带完整记忆文档");
            }
            ensureReady();
            List<List<Float>> vectors = embeddings.embedDocuments(List.of(document.content()));
            if (vectors == null || vectors.size() != 1) {
                throw new IllegalStateException("用户记忆 Embedding 数量不一致");
            }
            List<Float> vector = vectors.getFirst();
            if (vector == null || vector.size() != properties.getMilvus().getDimension()) {
                throw new IllegalStateException("用户记忆 Embedding 维度不一致");
            }
            keywordIndex.upsert(document);
            vectorIndex.upsert(document, vector);
        });
    }

    public void delete(long tenantId, long userId, long generation, String memoryId) {
        observed("DELETE", () -> {
            requireEnabled();
            ensureReady();
            keywordIndex.delete(tenantId, userId, generation, memoryId);
            vectorIndex.delete(tenantId, userId, generation, memoryId);
        });
    }

    public void deleteExplicitScope(long tenantId, long userId, long generation) {
        observed("DELETE_EXPLICIT_SCOPE", () -> {
            requireEnabled();
            ensureReady();
            keywordIndex.deleteExplicitScope(tenantId, userId, generation);
            vectorIndex.deleteExplicitScope(tenantId, userId, generation);
        });
    }

    public void clearGeneration(long tenantId, long userId, long generation) {
        observed("CLEAR_GENERATION", () -> {
            requireEnabled();
            ensureReady();
            keywordIndex.clearGeneration(tenantId, userId, generation);
            vectorIndex.clearGeneration(tenantId, userId, generation);
        });
    }

    private void observed(String operation, Runnable action) {
        try {
            action.run();
            if (metrics != null) metrics.recordUserMemoryIndex(operation, "SUCCESS");
        } catch (RuntimeException exception) {
            if (metrics != null) metrics.recordUserMemoryIndex(operation, "FAILURE");
            throw exception;
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("用户记忆索引未启用");
        }
    }

    private void ensureReady() {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (!ready) {
                keywordIndex.ensureReady();
                vectorIndex.ensureReady();
                ready = true;
            }
        }
    }
}

package com.xjjk.knowledge.usermemory.index;

import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;

import java.util.List;

public interface MemoryVectorIndex {
    void ensureReady();
    void upsert(MemoryIndexDocument document, List<Float> vector);
    void delete(long tenantId, long userId, long generation, String memoryId);
    void deleteExplicitScope(long tenantId, long userId, long generation);
    void clearGeneration(long tenantId, long userId, long generation);
    List<MemorySearchHit> search(
            long tenantId, long userId, long generation, List<Float> vector, int topK);
}

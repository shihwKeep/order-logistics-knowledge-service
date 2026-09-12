package com.xjjk.knowledge.usermemory.index;

import com.xjjk.knowledge.retrieval.index.SearchIndexUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "knowledge.retrieval.milvus",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class UnavailableMemoryMilvusGateway implements MemoryMilvusGateway {
    private SearchIndexUnavailableException unavailable() {
        return new SearchIndexUnavailableException("Milvus 未启用");
    }

    @Override public MemoryMilvusSpec describe(String collection) { throw unavailable(); }
    @Override public void create(MemoryMilvusSpec spec) { throw unavailable(); }
    @Override public void load(String collection) { throw unavailable(); }
    @Override public long upsert(String collection, MemoryVectorRow row) { throw unavailable(); }
    @Override public List<MemorySearchHit> search(
            String collection, String filter, List<Float> vector, int topK) { throw unavailable(); }
    @Override public void delete(String collection, String filter) { throw unavailable(); }
}

package com.xjjk.knowledge.usermemory.index;

import java.util.List;

public interface MemoryMilvusGateway {
    MemoryMilvusSpec describe(String collection);
    void create(MemoryMilvusSpec spec);
    void load(String collection);
    long upsert(String collection, MemoryVectorRow row);
    List<MemorySearchHit> search(
            String collection, String filter, List<Float> vector, int topK);
    void delete(String collection, String filter);
}

package com.xjjk.knowledge.retrieval.indexing;

public interface DerivedIndexStoreAdmin {
    void deleteElasticsearchIndexIfExists(String indexName);
    void dropMilvusCollectionIfExists(String collectionName);
}

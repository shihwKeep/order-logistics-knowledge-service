package com.xjjk.knowledge.retrieval.index;

import java.util.List;
import java.util.Map;

/** 隔离 Milvus SDK 细节，领域层只处理稳定行和过滤表达式。 */
public interface MilvusGateway {
    MilvusCollectionSpec describe(String collection);
    void create(MilvusCollectionSpec spec);
    void load(String collection);
    long upsert(String collection, List<MilvusVectorRow> rows);
    List<MilvusMatch> search(String collection, String filter, List<Float> vector, int topK);
    Map<String, String> fingerprints(String collection, String filter, int limit);
    void delete(String collection, String filter);
}

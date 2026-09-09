package com.xjjk.knowledge.retrieval.embedding;

import java.util.List;

/** 将知识正文和查询转换到同一受版本约束的向量空间。 */
public interface EmbeddingClient {
    List<List<Float>> embedDocuments(List<String> documents);

    List<Float> embedQuery(String query);
}

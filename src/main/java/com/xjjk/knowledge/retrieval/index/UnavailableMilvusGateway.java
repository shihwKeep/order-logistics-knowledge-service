package com.xjjk.knowledge.retrieval.index;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Milvus 未启用时的显式失败适配器，由检索编排层决定是否降级到 ES。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.retrieval.milvus", name = "enabled", havingValue = "false", matchIfMissing = true)
public class UnavailableMilvusGateway implements MilvusGateway {
    private SearchIndexUnavailableException unavailable() {
        return new SearchIndexUnavailableException("Milvus 未启用");
    }

    @Override public MilvusCollectionSpec describe(String collection) { throw unavailable(); }
    @Override public void create(MilvusCollectionSpec spec) { throw unavailable(); }
    @Override public void load(String collection) { throw unavailable(); }
    @Override public long upsert(String collection, List<MilvusVectorRow> rows) { throw unavailable(); }
    @Override public List<MilvusMatch> search(String collection, String filter, List<Float> vector, int topK) { throw unavailable(); }
    @Override public Map<String, String> fingerprints(String collection, String filter, int limit) { throw unavailable(); }
    @Override public void delete(String collection, String filter) { throw unavailable(); }
}

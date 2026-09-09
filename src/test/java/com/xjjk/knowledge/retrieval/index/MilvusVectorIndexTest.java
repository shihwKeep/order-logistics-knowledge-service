package com.xjjk.knowledge.retrieval.index;

import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusVectorIndexTest {

    @Test
    void createsIsolatedCollectionsAndBuildsTenantScopedSearch() {
        CapturingGateway gateway = new CapturingGateway();
        MilvusProperties properties = new MilvusProperties();
        properties.setDimension(4);
        MilvusVectorIndex index = new MilvusVectorIndex(gateway, properties);
        IndexChunk chunk = chunk();

        index.ensureReady();
        index.replaceVersion(IndexLayer.DRAFT, List.of(chunk), List.of(List.of(1F, 0F, 0F, 0F)));
        var hits = index.search(IndexLayer.PUBLISHED, 1L, List.of(2L), List.of(1F, 0F, 0F, 0F), 30);

        assertThat(gateway.created).extracting(MilvusCollectionSpec::name)
                .containsExactlyInAnyOrder("knowledge_chunks_draft_v1", "knowledge_chunks_published_v1");
        assertThat(gateway.created).allSatisfy(spec -> {
            assertThat(spec.dimension()).isEqualTo(4);
            assertThat(spec.metric()).isEqualTo("COSINE");
            assertThat(spec.primaryKeyField()).isEqualTo("chunk_id");
        });
        assertThat(gateway.lastFilter).contains("tenant_id == 1").contains("knowledge_base_id in [2]");
        assertThat(hits).singleElement().satisfies(hit -> assertThat(hit.chunk().chunkId()).isEqualTo("1-3-4-0"));
    }

    @Test
    void rejectsExistingCollectionWithDifferentDimension() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.described = new MilvusCollectionSpec("knowledge_chunks_draft_v1", 1024, "COSINE", "chunk_id");
        MilvusProperties properties = new MilvusProperties();
        properties.setDimension(2560);

        assertThatThrownBy(() -> new MilvusVectorIndex(gateway, properties).ensureReady())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度");
    }

    @Test
    void rejectsExistingCollectionWithDifferentMetric() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.described = new MilvusCollectionSpec("knowledge_chunks_draft_v1", 2560, "L2", "chunk_id");

        assertThatThrownBy(() -> new MilvusVectorIndex(gateway, new MilvusProperties()).ensureReady())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Schema");
    }

    private IndexChunk chunk() {
        return new IndexChunk("1-3-4-0", 1L, 2L, 3L, 4L, 0,
                "退款规则", "售后", "签收后七日内可申请退款", "abc", "{\"pageNumber\":3}");
    }

    private static final class CapturingGateway implements MilvusGateway {
        private final List<MilvusCollectionSpec> created = new ArrayList<>();
        private MilvusCollectionSpec described;
        private String lastFilter;
        private final Map<String, String> fingerprints = new LinkedHashMap<>();

        @Override
        public MilvusCollectionSpec describe(String collection) {
            return described;
        }

        @Override
        public void create(MilvusCollectionSpec spec) {
            created.add(spec);
        }

        @Override
        public void load(String collection) {
        }

        @Override
        public long upsert(String collection, List<MilvusVectorRow> rows) {
            rows.forEach(row -> fingerprints.put(row.chunk().chunkId(), row.chunk().contentSha256()));
            return rows.size();
        }

        @Override
        public List<MilvusMatch> search(String collection, String filter, List<Float> vector, int topK) {
            lastFilter = filter;
            return List.of(new MilvusMatch(new IndexChunk("1-3-4-0", 1L, 2L, 3L, 4L, 0,
                    "退款规则", "售后", "内容", "abc", "{}"), 0.93));
        }

        @Override
        public Map<String, String> fingerprints(String collection, String filter, int limit) {
            return fingerprints;
        }

        @Override
        public void delete(String collection, String filter) {
            fingerprints.clear();
        }
    }
}

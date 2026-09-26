package com.xjjk.knowledge.retrieval.index;

import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.DocumentVersionRef;
import com.xjjk.knowledge.retrieval.service.RetrievalProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MilvusVectorIndexTest {

    @Test
    void filtersPublishedSearchByExactVersionPairsAndMergesBatches() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.searchResults = List.of(
                List.of(match("shared", 0.70D, 3L, 11L), match("first", 0.60D, 4L, 12L)),
                List.of(match("shared", 0.95D, 3L, 11L), match("second", 0.80D, 5L, 13L)));
        MilvusProperties milvus = new MilvusProperties();
        milvus.setDimension(4);
        RetrievalProperties retrieval = new RetrievalProperties();
        retrieval.setReleaseFilterBatchSize(2);
        MilvusVectorIndex index = new MilvusVectorIndex(gateway, milvus, retrieval);
        List<DocumentVersionRef> versions = List.of(
                new DocumentVersionRef(2L, 3L, 11L),
                new DocumentVersionRef(2L, 4L, 12L),
                new DocumentVersionRef(2L, 5L, 13L));

        var hits = index.search(
                IndexLayer.PUBLISHED,
                1L,
                List.of(2L),
                versions,
                List.of(1F, 0F, 0F, 0F),
                2);

        assertThat(hits).extracting(hit -> hit.chunk().chunkId())
                .containsExactly("shared", "second");
        assertThat(hits).extracting(hit -> hit.score()).containsExactly(0.95D, 0.80D);
        assertThat(gateway.filters).hasSize(2);
        assertThat(gateway.filters.getFirst())
                .contains("tenant_id == 1")
                .contains("knowledge_base_id in [2]")
                .contains("knowledge_base_id == 2 && document_id == 3 && version_id == 11")
                .contains("knowledge_base_id == 2 && document_id == 4 && version_id == 12")
                .doesNotContain("document_id in [3,4]")
                .doesNotContain("version_id in [11,12]");
        assertThat(gateway.filters.get(1))
                .contains("knowledge_base_id == 2 && document_id == 5 && version_id == 13");
    }

    @Test
    void createsIsolatedCollectionsAndBuildsTenantScopedSearch() {
        CapturingGateway gateway = new CapturingGateway();
        MilvusProperties properties = new MilvusProperties();
        properties.setDimension(4);
        MilvusVectorIndex index = new MilvusVectorIndex(
                gateway, properties, new RetrievalProperties());
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
        assertThat(gateway.lastRows).singleElement().satisfies(row ->
                assertThat(row.chunk().titlePath()).isEqualTo("退款规范 > 5 优惠处理"));
        assertThat(hits).singleElement().satisfies(hit -> assertThat(hit.chunk().chunkId()).isEqualTo("1-3-4-0"));
    }

    @Test
    void rejectsExistingCollectionWithDifferentDimension() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.described = new MilvusCollectionSpec("knowledge_chunks_draft_v1", 1024, "COSINE", "chunk_id");
        MilvusProperties properties = new MilvusProperties();
        properties.setDimension(2560);

        assertThatThrownBy(() -> new MilvusVectorIndex(
                gateway, properties, new RetrievalProperties()).ensureReady())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度");
    }

    @Test
    void rejectsExistingCollectionWithDifferentMetric() {
        CapturingGateway gateway = new CapturingGateway();
        gateway.described = new MilvusCollectionSpec("knowledge_chunks_draft_v1", 2560, "L2", "chunk_id");

        assertThatThrownBy(() -> new MilvusVectorIndex(
                gateway, new MilvusProperties(), new RetrievalProperties()).ensureReady())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Schema");
    }

    private IndexChunk chunk() {
        return new IndexChunk("1-3-4-0", 1L, 2L, 3L, 4L, 0,
                "退款规则", "退款规范 > 5 优惠处理", "签收后七日内可申请退款", "abc",
                "{\"pageNumber\":3}");
    }

    private static MilvusMatch match(String chunkId, double score, long documentId, long versionId) {
        return new MilvusMatch(new IndexChunk(
                chunkId, 1L, 2L, documentId, versionId, 0,
                "退款规则", "售后", "内容", "abc", "{}"), score);
    }

    private static final class CapturingGateway implements MilvusGateway {
        private final List<MilvusCollectionSpec> created = new ArrayList<>();
        private MilvusCollectionSpec described;
        private String lastFilter;
        private final List<String> filters = new ArrayList<>();
        private List<MilvusVectorRow> lastRows = List.of();
        private final Map<String, String> fingerprints = new LinkedHashMap<>();
        private List<List<MilvusMatch>> searchResults = List.of();

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
            lastRows = List.copyOf(rows);
            rows.forEach(row -> fingerprints.put(row.chunk().chunkId(), row.chunk().contentSha256()));
            return rows.size();
        }

        @Override
        public List<MilvusMatch> search(String collection, String filter, List<Float> vector, int topK) {
            lastFilter = filter;
            filters.add(filter);
            if (!searchResults.isEmpty()) {
                return searchResults.get(Math.min(filters.size() - 1, searchResults.size() - 1));
            }
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

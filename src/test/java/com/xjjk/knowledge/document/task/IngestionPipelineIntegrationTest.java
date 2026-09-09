package com.xjjk.knowledge.document.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.SourceFile;
import com.xjjk.knowledge.document.parser.DocumentParserRegistry;
import com.xjjk.knowledge.document.parser.TextDocumentParser;
import com.xjjk.knowledge.document.persistence.DocumentMapper;
import com.xjjk.knowledge.document.persistence.MybatisDocumentRepository;
import com.xjjk.knowledge.document.processing.ChunkingProperties;
import com.xjjk.knowledge.document.processing.ConservativeTokenEstimator;
import com.xjjk.knowledge.document.processing.StructuralChunker;
import com.xjjk.knowledge.document.processing.TextNormalizer;
import com.xjjk.knowledge.document.storage.SourceObjectStore;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingClient;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingProperties;
import com.xjjk.knowledge.retrieval.index.IndexVerification;
import com.xjjk.knowledge.retrieval.index.KeywordIndex;
import com.xjjk.knowledge.retrieval.index.VectorIndex;
import com.xjjk.knowledge.retrieval.indexing.ChunkIndexMapper;
import com.xjjk.knowledge.retrieval.indexing.DraftIndexingService;
import com.xjjk.knowledge.retrieval.indexing.MybatisChunkIndexRepository;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.RecallCandidate;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class IngestionPipelineIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge").withUsername("knowledge").withPassword("knowledge");

    @Test
    void textUploadRunsParseThenRetriesIndexAndReachesReady() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DocumentMapper.class);
        configuration.addMapper(IngestionTaskMapper.class);
        configuration.addMapper(IngestionArtifactMapper.class);
        configuration.addMapper(ChunkIndexMapper.class);
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            DocumentMapper documentsMapper = session.getMapper(DocumentMapper.class);
            MybatisDocumentRepository documents = new MybatisDocumentRepository(documentsMapper);
            CreatedDocument created = documents.createDraft(
                    1L, 2L, 10567L, "退款规则",
                    new SourceFile("refund.txt", "txt", "text/plain", 18L,
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
            long taskId = session.getMapper(IngestionTaskMapper.class).findTaskId(1L, created.version().id());
            IngestionProperties properties = new IngestionProperties();
            MemoryIndex keyword = new MemoryIndex();
            MemoryIndex vector = new MemoryIndex();
            EmbeddingProperties embeddingProperties = new EmbeddingProperties();
            embeddingProperties.setDimension(4);
            EmbeddingClient embeddings = new EmbeddingClient() {
                @Override public List<List<Float>> embedDocuments(List<String> values) {
                    return values.stream().map(value -> List.of(1F, 0F, 0F, 0F)).toList();
                }
                @Override public List<Float> embedQuery(String query) { throw new UnsupportedOperationException(); }
            };
            DraftIndexingService indexing = new DraftIndexingService(
                    new MybatisChunkIndexRepository(session.getMapper(ChunkIndexMapper.class)),
                    embeddings, embeddingProperties, keyword, vector);
            IngestionWorker worker = new IngestionWorker(
                    new MybatisIngestionTaskRepository(session.getMapper(IngestionTaskMapper.class)),
                    documents,
                    new FixedObjectStore("签收后七日内可以申请退款。".getBytes(StandardCharsets.UTF_8)),
                    new DocumentParserRegistry(java.util.List.of(new TextDocumentParser())),
                    new IngestionArtifactRepository(session.getMapper(IngestionArtifactMapper.class), new TextNormalizer()),
                    new StructuralChunker(new ChunkingProperties(), new ConservativeTokenEstimator()),
                    indexing, properties);

            assertThat(worker.process(taskId)).isTrue();
            assertThat(documents.findVersion(1L, created.document().id(), created.version().id()).orElseThrow().status())
                    .isEqualTo(DocumentStatus.INDEXING);
            IngestionArtifactMapper artifacts = session.getMapper(IngestionArtifactMapper.class);
            assertThat(artifacts.listUnits(1L, created.document().id(), created.version().id())).hasSize(1);
            IngestionTask completed = session.selectOne(
                    "com.xjjk.knowledge.document.task.IngestionTaskMapper.find", taskId);
            assertThat(completed.status()).isEqualTo("DONE");
            IngestionTask indexTask = session.getMapper(IngestionTaskMapper.class)
                    .findLatestForVersion(1L, created.version().id());
            assertThat(indexTask.stage()).isEqualTo("INDEX");
            assertThat(indexTask.status()).isEqualTo("PENDING");

            vector.failNextReplace = true;
            assertThat(worker.process(indexTask.id())).isFalse();
            assertThat(documents.findVersion(1L, created.document().id(), created.version().id()).orElseThrow().status())
                    .isEqualTo(DocumentStatus.FAILED);
            try (java.sql.Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE kb_ingestion_task SET next_run_at=CURRENT_TIMESTAMP(3) "
                        + "WHERE id=" + indexTask.id());
            }

            assertThat(worker.process(indexTask.id())).isTrue();
            var ready = documents.findVersion(1L, created.document().id(), created.version().id()).orElseThrow();
            assertThat(ready.status()).isEqualTo(DocumentStatus.READY);
            assertThat(ready.embeddingDimension()).isEqualTo(4);
            assertThat(ready.indexManifestSha256()).hasSize(64);
        }
    }

    private static final class MemoryIndex implements KeywordIndex, VectorIndex {
        private final Map<String, String> fingerprints = new LinkedHashMap<>();
        private boolean failNextReplace;
        @Override public void ensureReady() {}
        @Override public void replaceVersion(IndexLayer layer, List<IndexChunk> chunks) {
            if (failNextReplace) {
                failNextReplace = false;
                throw new IllegalStateException("simulated index failure");
            }
            fingerprints.clear();
            chunks.forEach(chunk -> fingerprints.put(chunk.chunkId(), chunk.contentSha256()));
        }
        @Override public void replaceVersion(IndexLayer layer, List<IndexChunk> chunks, List<List<Float>> vectors) {
            replaceVersion(layer, chunks);
        }
        @Override public List<RecallCandidate> search(IndexLayer layer, long tenantId, List<Long> ids, String query, int topK) { return List.of(); }
        @Override public List<RecallCandidate> search(IndexLayer layer, long tenantId, List<Long> ids, List<Float> vector, int topK) { return List.of(); }
        @Override public IndexVerification verifyVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {
            return new IndexVerification(fingerprints);
        }
        @Override public void deleteVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {}
    }

    private record FixedObjectStore(byte[] content) implements SourceObjectStore {
        @Override public void put(String objectKey, InputStream input, long size, String contentType) {}
        @Override public InputStream get(String objectKey) { return new ByteArrayInputStream(content); }
        @Override public void putParsed(String objectKey, byte[] value, String contentType) {}
    }
}

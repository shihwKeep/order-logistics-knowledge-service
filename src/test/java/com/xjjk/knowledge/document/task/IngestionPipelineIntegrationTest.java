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
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    void textUploadTaskReachesIndexingWithPersistedUnitsAndChunks() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DocumentMapper.class);
        configuration.addMapper(IngestionTaskMapper.class);
        configuration.addMapper(IngestionArtifactMapper.class);
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            DocumentMapper documentsMapper = session.getMapper(DocumentMapper.class);
            MybatisDocumentRepository documents = new MybatisDocumentRepository(documentsMapper);
            CreatedDocument created = documents.createDraft(
                    1L, 2L, 10567L, "退款规则",
                    new SourceFile("refund.txt", "txt", "text/plain", 18L,
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
            long taskId = session.getMapper(IngestionTaskMapper.class).findTaskId(1L, created.version().id());
            IngestionProperties properties = new IngestionProperties();
            IngestionWorker worker = new IngestionWorker(
                    new MybatisIngestionTaskRepository(session.getMapper(IngestionTaskMapper.class)),
                    documents,
                    new FixedObjectStore("签收后七日内可以申请退款。".getBytes(StandardCharsets.UTF_8)),
                    new DocumentParserRegistry(java.util.List.of(new TextDocumentParser())),
                    new IngestionArtifactRepository(session.getMapper(IngestionArtifactMapper.class), new TextNormalizer()),
                    new StructuralChunker(new ChunkingProperties(), new ConservativeTokenEstimator()),
                    properties);

            assertThat(worker.process(taskId)).isTrue();
            assertThat(documents.findVersion(1L, created.document().id(), created.version().id()).orElseThrow().status())
                    .isEqualTo(DocumentStatus.INDEXING);
            IngestionArtifactMapper artifacts = session.getMapper(IngestionArtifactMapper.class);
            assertThat(artifacts.listUnits(1L, created.document().id(), created.version().id())).hasSize(1);
            IngestionTask completed = session.selectOne(
                    "com.xjjk.knowledge.document.task.IngestionTaskMapper.find", taskId);
            assertThat(completed.status()).isEqualTo("DONE");
        }
    }

    private record FixedObjectStore(byte[] content) implements SourceObjectStore {
        @Override public void put(String objectKey, InputStream input, long size, String contentType) {}
        @Override public InputStream get(String objectKey) { return new ByteArrayInputStream(content); }
        @Override public void putParsed(String objectKey, byte[] value, String contentType) {}
    }
}

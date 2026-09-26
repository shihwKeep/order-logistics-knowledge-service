package com.xjjk.knowledge.publication.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Statement;
import java.util.List;
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
class ReleaseRepositoryIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @Test
    void storesImmutableManifestTaskAndOutboxInOneRepositoryBoundary() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ReleaseMapper.class);
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO kb_knowledge_base(id,tenant_id,name,status,created_by,updated_by)
                        VALUES(7,1,'售后知识库','ENABLED',10567,10567)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document(id,tenant_id,knowledge_base_id,title,created_by,updated_by)
                        VALUES(12,1,7,'退款规则',10567,10567)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document_version(
                          id,tenant_id,knowledge_base_id,document_id,version_number,status,
                          original_filename,file_extension,mime_type,file_size,source_sha256,
                          upload_request_id,source_object_key,index_manifest_sha256,created_by)
                        VALUES(102,1,7,12,2,'READY','refund.pdf','pdf','application/pdf',10,
                          REPEAT('a',64),'upload-102','source/102',REPEAT('b',64),10567)
                        """);
            }
            MybatisReleaseRepository repository = new MybatisReleaseRepository(
                    session.getMapper(ReleaseMapper.class));

            ReleaseBaseline baseline = repository.lockBaseline(1L, 7L);
            assertThat(baseline.currentReleaseId()).isNull();
            assertThat(repository.findReadyItem(1L, 7L, 12L, 102L)).isPresent();
            KnowledgeRelease release = repository.insert(KnowledgeRelease.preparing(
                    1L, 7L, 1, null, "request-1", "c".repeat(64),
                    baseline.rowVersion(), 10567L));
            repository.insertItems(release.id(), List.of(
                    new ReleaseItem(release.id(), 1L, 7L, 12L, 102L, "b".repeat(64))));
            repository.enqueue(release);

            assertThat(repository.findByRequest(1L, "request-1")).contains(release);
            assertThat(repository.items(release.id())).hasSize(1);
            try (var statement = session.getConnection().prepareStatement(
                    "SELECT COUNT(*) FROM kb_release_task WHERE release_id=?")) {
                statement.setLong(1, release.id());
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
            try (var statement = session.getConnection().prepareStatement("""
                    SELECT COUNT(*) FROM kb_outbox_event
                     WHERE aggregate_type='KNOWLEDGE_RELEASE' AND aggregate_id=?
                       AND event_type='KNOWLEDGE_RELEASE_REQUESTED'
                    """)) {
                statement.setString(1, Long.toString(release.id()));
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
        }
    }
}

package com.xjjk.knowledge.publication.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.xjjk.knowledge.retrieval.model.IndexLayer;
import java.sql.Statement;
import java.time.Duration;
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
class DerivedCleanupRepositoryIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @Test
    void leasesTaskMapsLayerAndProtectsCurrentDraftAndReleaseReferences() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DerivedCleanupMapper.class);
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO kb_knowledge_base(id,tenant_id,name,status,created_by,updated_by)
                        VALUES(2,1,'售后知识库','ENABLED',10567,10567)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document(id,tenant_id,knowledge_base_id,title,current_draft_version_id,
                          created_by,updated_by)
                        VALUES(3,1,2,'退款规则',4,10567,10567)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document_version(
                          id,tenant_id,knowledge_base_id,document_id,version_number,status,
                          original_filename,file_extension,mime_type,file_size,source_sha256,
                          upload_request_id,source_object_key,index_manifest_sha256,created_by)
                        VALUES(4,1,2,3,1,'READY','v1.txt','txt','text/plain',10,REPEAT('a',64),
                          'upload-v1','source/v1',REPEAT('b',64),10567)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_derived_index_cleanup(
                          id,tenant_id,knowledge_base_id,document_id,version_id,index_layer,
                          cleanup_reason,status)
                        VALUES(7,1,2,3,4,'DRAFT','DRAFT_REPLACED','PENDING')
                        """);
            }
            DerivedCleanupRepository repository = new DerivedCleanupRepository(
                    session.getMapper(DerivedCleanupMapper.class));

            DerivedCleanupTask task = repository.claim(
                    7L, "test-worker", Duration.ofMinutes(1)).orElseThrow();
            assertThat(task.layer()).isEqualTo(IndexLayer.DRAFT);
            assertThat(task.leaseToken()).isNotBlank();
            assertThat(repository.isReferenced(task)).isTrue();

            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE kb_document SET current_draft_version_id=NULL WHERE id=3");
                statement.executeUpdate("""
                        INSERT INTO kb_release(
                          id,tenant_id,knowledge_base_id,release_number,status,request_id,
                          manifest_sha256,base_row_version,created_by)
                        VALUES(9,1,2,1,'ACTIVE','release-1',REPEAT('c',64),0,10567)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_release_item(
                          release_id,tenant_id,knowledge_base_id,document_id,version_id,content_manifest_sha256)
                        VALUES(9,1,2,3,4,REPEAT('b',64))
                        """);
                statement.executeUpdate("UPDATE kb_knowledge_base SET current_release_id=9 WHERE id=2");
            }
            assertThat(repository.isReferenced(task)).isTrue();

            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE kb_knowledge_base SET current_release_id=NULL WHERE id=2");
            }
            assertThat(repository.isReferenced(task)).isFalse();
            assertThat(repository.completeOwned(task.id(), task.leaseToken())).isTrue();
        }
    }
}

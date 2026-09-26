package com.xjjk.knowledge.publication.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Statement;
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
class ReleaseActivationIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @Test
    void atomicallyActivatesOneReleaseAndRejectsAnotherWithStaleBaseline() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ReleaseMapper.class);
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            seed(session);
            MybatisReleaseRepository repository = new MybatisReleaseRepository(
                    session.getMapper(ReleaseMapper.class));
            KnowledgeRelease releaseA = repository.find(1L, 7L, 51L).orElseThrow();
            KnowledgeRelease releaseB = repository.find(1L, 7L, 52L).orElseThrow();
            ReleaseTaskLease leaseA = new ReleaseTaskLease(71L, 51L, 1L, 7L, "lease-a");
            ReleaseTaskLease leaseB = new ReleaseTaskLease(72L, 52L, 1L, 7L, "lease-b");

            repository.activate(releaseA, leaseA);

            assertThat(repository.currentReleaseId(1L, 7L)).isEqualTo(51L);
            assertThat(repository.find(1L, 7L, 51L).orElseThrow().status())
                    .isEqualTo(ReleaseStatus.ACTIVE);
            assertThat(repository.find(1L, 7L, 50L).orElseThrow().status())
                    .isEqualTo(ReleaseStatus.SUPERSEDED);
            assertThatThrownBy(() -> repository.activate(releaseB, leaseB))
                    .isInstanceOf(ReleaseConflictException.class);
            assertThat(repository.currentReleaseId(1L, 7L)).isEqualTo(51L);
        }
    }

    private static void seed(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO kb_knowledge_base(
                      id,tenant_id,name,status,current_release_id,created_by,updated_by,row_version)
                    VALUES(7,1,'售后知识库','ENABLED',50,10567,10567,6)
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_document(
                      id,tenant_id,knowledge_base_id,title,current_draft_version_id,
                      current_published_version_id,created_by,updated_by)
                    VALUES(12,1,7,'退款规则',102,92,10567,10567)
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_document_version(
                      id,tenant_id,knowledge_base_id,document_id,version_number,status,
                      original_filename,file_extension,mime_type,file_size,source_sha256,
                      upload_request_id,source_object_key,index_manifest_sha256,created_by)
                    VALUES
                      (92,1,7,12,1,'PUBLISHED','v1.pdf','pdf','application/pdf',10,REPEAT('a',64),
                       'upload-92','source/92',REPEAT('b',64),10567),
                      (102,1,7,12,2,'READY','v2.pdf','pdf','application/pdf',10,REPEAT('c',64),
                       'upload-102','source/102',REPEAT('d',64),10567)
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_release(
                      id,tenant_id,knowledge_base_id,release_number,status,base_release_id,request_id,
                      manifest_sha256,base_row_version,created_by)
                    VALUES
                      (50,1,7,1,'ACTIVE',NULL,'release-1',REPEAT('e',64),5,10567),
                      (51,1,7,2,'PREPARING',50,'release-2',REPEAT('f',64),6,10567),
                      (52,1,7,3,'PREPARING',50,'release-3',REPEAT('0',64),6,10567)
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_release_item(
                      release_id,tenant_id,knowledge_base_id,document_id,version_id,content_manifest_sha256)
                    VALUES
                      (50,1,7,12,92,REPEAT('b',64)),
                      (51,1,7,12,102,REPEAT('d',64)),
                      (52,1,7,12,102,REPEAT('d',64))
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_release_task(
                      id,release_id,tenant_id,knowledge_base_id,status,lease_token,locked_by,locked_until)
                    VALUES
                      (71,51,1,7,'PROCESSING','lease-a','worker',DATE_ADD(NOW(3),INTERVAL 1 MINUTE)),
                      (72,52,1,7,'PROCESSING','lease-b','worker',DATE_ADD(NOW(3),INTERVAL 1 MINUTE))
                    """);
        }
    }
}

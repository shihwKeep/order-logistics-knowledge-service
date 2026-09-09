package com.xjjk.knowledge.publication;

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

import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PublicationRepositoryIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge").withUsername("knowledge").withPassword("knowledge");

    @Test
    void switchesPointerCancelsReactivatedVersionCleanupAndDurablyRegistersDisableCleanup() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PublicationMapper.class);
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO kb_knowledge_base(id,tenant_id,name,status,created_by,updated_by)
                        VALUES(2,1,'售后知识库','ENABLED',1,1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document(id,tenant_id,knowledge_base_id,title,current_draft_version_id,
                                                created_by,updated_by,row_version)
                        VALUES(3,1,2,'退款规则',4,1,1,0)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document_version
                          (id,tenant_id,knowledge_base_id,document_id,version_number,status,original_filename,
                           file_extension,mime_type,file_size,source_sha256,source_object_key,embedding_model,
                           embedding_dimension,embedding_instruction_version,index_manifest_sha256,indexed_at,
                           unit_count,chunk_count,created_by)
                        VALUES(4,1,2,3,1,'READY','refund.txt','txt','text/plain',10,
                           REPEAT('a',64),'key','qwen',2560,'instruction',REPEAT('b',64),CURRENT_TIMESTAMP(3),1,1,10567)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document_version
                          (id,tenant_id,knowledge_base_id,document_id,version_number,status,original_filename,
                           file_extension,mime_type,file_size,source_sha256,source_object_key,embedding_model,
                           embedding_dimension,embedding_instruction_version,index_manifest_sha256,indexed_at,
                           unit_count,chunk_count,created_by)
                        VALUES(5,1,2,3,2,'READY','refund-v2.txt','txt','text/plain',11,
                           REPEAT('c',64),'key-v2','qwen',2560,'instruction',REPEAT('d',64),CURRENT_TIMESTAMP(3),1,1,10567)
                        """);
            }
            PublicationMapper mapper = session.getMapper(PublicationMapper.class);
            MybatisPublicationRepository repository = new MybatisPublicationRepository(mapper);
            PublicationTarget target = repository.loadVersionTarget(1L, 2L, 3L, 4L);

            PublicationRecord published = repository.activate(
                    target, PublicationAction.PUBLISH, 10567L, "publish-request");
            PublicationRecord duplicate = repository.activate(
                    target, PublicationAction.PUBLISH, 10567L, "publish-request");

            assertThat(duplicate.id()).isEqualTo(published.id());
            assertThat(mapper.findDocument(1L, 2L, 3L).getCurrentPublishedVersionId()).isEqualTo(4L);

            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE kb_document SET current_draft_version_id=5 WHERE id=3");
            }
            PublicationTarget second = repository.loadVersionTarget(1L, 2L, 3L, 5L);
            repository.activate(second, PublicationAction.PUBLISH, 10567L, "publish-v2-request");
            PublicationTarget firstAgain = repository.loadVersionTarget(1L, 2L, 3L, 4L);
            repository.activate(firstAgain, PublicationAction.ROLLBACK, 10567L, "rollback-v1-request");

            assertThat(mapper.findDueCleanup(20))
                    .extracting(PublicationCleanupTask::versionId)
                    .containsExactly(5L);
            PublicationTarget active = repository.loadCurrentPublishedTarget(1L, 2L, 3L);
            PublicationRecord disabled = repository.disable(active, 10567L, "disable-request");

            assertThat(disabled.action()).isEqualTo(PublicationAction.DISABLE);
            assertThat(repository.loadVersionTarget(1L, 2L, 3L, 4L).version().status().name())
                    .isEqualTo("ARCHIVED");
            assertThat(mapper.findDueCleanup(20))
                    .extracting(PublicationCleanupTask::versionId)
                    .containsExactly(5L, 4L);
        }
    }
}

package com.xjjk.knowledge.retrieval.indexing;

import static org.assertj.core.api.Assertions.assertThat;

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
class DraftPromotionIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @Test
    void delayedOlderVersionCannotReplaceTheLatestReadyDraft() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChunkIndexMapper.class);

        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(false)) {
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO kb_knowledge_base(id,tenant_id,name,status,created_by,updated_by)
                        VALUES(2,1,'售后知识库','ENABLED',10567,10567)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document(
                          id,tenant_id,knowledge_base_id,title,current_draft_version_id,
                          created_by,updated_by)
                        VALUES(3,1,2,'退款规则',4,10567,10567)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document_version(
                          id,tenant_id,knowledge_base_id,document_id,version_number,status,
                          original_filename,file_extension,mime_type,file_size,source_sha256,
                          upload_request_id,source_object_key,chunk_count,created_by)
                        VALUES
                          (4,1,2,3,1,'READY','v1.txt','txt','text/plain',10,REPEAT('a',64),
                           'upload-v1','source/v1',0,10567),
                          (5,1,2,3,2,'INDEXING','v2.txt','txt','text/plain',10,REPEAT('b',64),
                           'upload-v2','source/v2',0,10567),
                          (6,1,2,3,3,'INDEXING','v3.txt','txt','text/plain',10,REPEAT('c',64),
                           'upload-v3','source/v3',0,10567)
                        """);
            }
            ChunkIndexMapper mapper = session.getMapper(ChunkIndexMapper.class);

            assertThat(mapper.lockCurrentDraft(1L, 2L, 3L)).isEqualTo(4L);
            assertThat(markReady(mapper, 6L)).isEqualTo(1);
            assertThat(mapper.promoteLatestDraft(1L, 2L, 3L, 6L, 3, 10567L)).isEqualTo(1);
            assertThat(mapper.insertDraftCleanup(1L, 2L, 3L, 4L, "DRAFT_REPLACED")).isEqualTo(1);

            // v2 的索引任务晚于 v3 完成：允许记录 READY，但不得把草稿指针回退到旧版本。
            assertThat(markReady(mapper, 5L)).isEqualTo(1);
            assertThat(mapper.promoteLatestDraft(1L, 2L, 3L, 5L, 2, 10567L)).isZero();
            session.commit();

            assertThat(mapper.lockCurrentDraft(1L, 2L, 3L)).isEqualTo(6L);
            try (var statement = session.getConnection().prepareStatement("""
                    SELECT id,status FROM kb_document_version WHERE id IN (5,6) ORDER BY id
                    """); var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("id")).isEqualTo(5L);
                assertThat(result.getString("status")).isEqualTo("READY");
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("id")).isEqualTo(6L);
                assertThat(result.getString("status")).isEqualTo("READY");
                assertThat(result.next()).isFalse();
            }
            try (var statement = session.getConnection().prepareStatement("""
                    SELECT COUNT(*)
                      FROM kb_derived_index_cleanup
                     WHERE tenant_id=1 AND document_id=3 AND version_id=4
                       AND index_layer='DRAFT' AND cleanup_reason='DRAFT_REPLACED'
                    """); var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private static int markReady(ChunkIndexMapper mapper, long versionId) {
        return mapper.markReady(
                1L, 3L, versionId, 0, 0,
                "qwen3.7-text-embedding", 2560, "qwen37-customer-service-v2",
                "d".repeat(64), null, null);
    }
}

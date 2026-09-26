package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.retrieval.model.DocumentVersionRef;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ActiveReleaseScopeIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @Test
    void loadsOnlyEnabledActiveReleasesForRequestedTenantAndKnowledgeBases() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ActiveReleaseScopeMapper.class);

        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            seed(session);
            ActiveReleaseScopeLoader loader =
                    new ActiveReleaseScopeLoader(session.getMapper(ActiveReleaseScopeMapper.class));

            ActiveReleaseScope selected = loader.load(1L, List.of(2L, 3L));

            assertThat(selected.releaseIds())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(2L, 20L, 3L, 30L));
            assertThat(selected.versions()).containsExactly(
                    new DocumentVersionRef(2L, 8L, 11L),
                    new DocumentVersionRef(3L, 9L, 12L));

            ActiveReleaseScope allEnabled = loader.load(1L, List.of());

            assertThat(allEnabled.releaseIds())
                    .containsExactlyInAnyOrderEntriesOf(Map.of(2L, 20L, 3L, 30L));
            assertThat(allEnabled.versions()).containsExactly(
                    new DocumentVersionRef(2L, 8L, 11L),
                    new DocumentVersionRef(3L, 9L, 12L));
        }
    }

    private void seed(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO kb_knowledge_base(id,tenant_id,name,status,created_by,updated_by)
                    VALUES
                      (2,1,'售后知识库','ENABLED',1,1),
                      (3,1,'退款知识库','ENABLED',1,1),
                      (4,1,'停用知识库','DISABLED',1,1),
                      (5,2,'其他租户知识库','ENABLED',2,2)
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_release(
                      id,tenant_id,knowledge_base_id,release_number,status,base_release_id,
                      request_id,manifest_sha256,base_row_version,created_by)
                    VALUES
                      (19,1,2,1,'SUPERSEDED',NULL,'old-2',REPEAT('a',64),0,1),
                      (20,1,2,2,'ACTIVE',19,'active-2',REPEAT('b',64),0,1),
                      (30,1,3,1,'ACTIVE',NULL,'active-3',REPEAT('c',64),0,1),
                      (40,1,4,1,'ACTIVE',NULL,'disabled-4',REPEAT('d',64),0,1),
                      (50,2,5,1,'ACTIVE',NULL,'tenant-2',REPEAT('e',64),0,2)
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_release_item(
                      release_id,tenant_id,knowledge_base_id,document_id,version_id,content_manifest_sha256)
                    VALUES
                      (19,1,2,8,10,REPEAT('f',64)),
                      (20,1,2,8,11,REPEAT('1',64)),
                      (30,1,3,9,12,REPEAT('2',64)),
                      (40,1,4,10,13,REPEAT('3',64)),
                      (50,2,5,11,14,REPEAT('4',64))
                    """);
            statement.executeUpdate("""
                    UPDATE kb_knowledge_base
                       SET current_release_id=CASE id
                         WHEN 2 THEN 20 WHEN 3 THEN 30 WHEN 4 THEN 40 WHEN 5 THEN 50 END
                     WHERE id IN (2,3,4,5)
                    """);
        }
    }
}

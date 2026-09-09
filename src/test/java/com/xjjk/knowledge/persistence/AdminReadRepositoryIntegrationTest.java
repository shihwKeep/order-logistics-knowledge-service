package com.xjjk.knowledge.persistence;

import com.xjjk.knowledge.audit.AuditLogEntry;
import com.xjjk.knowledge.audit.AuditQueryMapper;
import com.xjjk.knowledge.document.service.DocumentChunkView;
import com.xjjk.knowledge.document.service.DocumentManagementMapper;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AdminReadRepositoryIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge").withUsername("knowledge").withPassword("knowledge");

    @Test
    void mapsFilteredAuditPageAndVersionChunks() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AuditQueryMapper.class);
        configuration.addMapper(DocumentManagementMapper.class);

        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            seed(session);
            AuditQueryMapper audits = session.getMapper(AuditQueryMapper.class);
            DocumentManagementMapper documents = session.getMapper(DocumentManagementMapper.class);

            assertThat(audits.count(7L, "DOCUMENT_PUBLISH", null, "req-1")).isEqualTo(1);
            List<AuditLogEntry> auditPage = audits.list(7L, "DOCUMENT_PUBLISH", null, "req-1", 0, 20);
            assertThat(auditPage).singleElement().satisfies(entry -> {
                assertThat(entry.actorUserId()).isEqualTo(10567L);
                assertThat(entry.detailJson()).contains("versionId");
            });

            List<DocumentChunkView> chunks = documents.listChunks(7L, 3L, 4L, 0, 20);
            assertThat(chunks).singleElement().satisfies(chunk -> {
                assertThat(chunk.content()).isEqualTo("签收后七日内可申请退款");
                assertThat(chunk.locationJson()).contains("pageNumber");
            });
        }
    }

    private void seed(SqlSession session) throws Exception {
        try (Statement statement = session.getConnection().createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO kb_audit_log
                      (tenant_id,actor_user_id,actor_tenant_id,action,resource_type,resource_id,
                       request_id,outcome,detail_json)
                    VALUES(7,10567,7,'DOCUMENT_PUBLISH','DOCUMENT_VERSION','4','req-1','SUCCESS',
                           JSON_OBJECT('versionId',4))
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_chunk
                      (tenant_id,knowledge_base_id,document_id,version_id,unit_id,chunk_index,title_path,
                       content,token_count,content_sha256,location_json)
                    VALUES(7,2,3,4,9,0,'售后/退款','签收后七日内可申请退款',12,REPEAT('a',64),
                           JSON_OBJECT('pageNumber',1))
                    """);
        }
    }
}

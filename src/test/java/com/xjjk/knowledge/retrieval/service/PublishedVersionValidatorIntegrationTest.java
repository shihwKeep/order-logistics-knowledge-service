package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import com.xjjk.knowledge.retrieval.model.RecallSource;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PublishedVersionValidatorIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge").withUsername("knowledge").withPassword("knowledge");

    @Test
    void acceptsOnlyCurrentPublishedVersionFromEnabledKnowledgeBase() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PublishedVersionMapper.class);
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO kb_knowledge_base(id,tenant_id,name,status,created_by,updated_by)
                        VALUES(2,1,'售后知识库','ENABLED',1,1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document(id,tenant_id,knowledge_base_id,title,current_published_version_id,created_by,updated_by)
                        VALUES(3,1,2,'退款规则',10,1,1)
                        """);
            }
            PublishedVersionValidator validator = new PublishedVersionValidator(session.getMapper(PublishedVersionMapper.class));

            List<RankedEvidence> result = validator.validate(1L, List.of(evidence(10L), evidence(9L)));

            assertThat(result).extracting(item -> item.chunk().versionId()).containsExactly(10L);
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE kb_knowledge_base SET status='DISABLED' WHERE tenant_id=1 AND id=2");
            }
            assertThat(validator.validate(1L, List.of(evidence(10L)))).isEmpty();
        }
    }

    private RankedEvidence evidence(long versionId) {
        IndexChunk chunk = new IndexChunk(
                "1-3-" + versionId + "-0", 1L, 2L, 3L, versionId, 0,
                "退款规则", "售后", "正文", "hash", "{}");
        return new RankedEvidence(chunk, 0.8D, 0.03D, Set.of(RecallSource.KEYWORD), 1);
    }
}

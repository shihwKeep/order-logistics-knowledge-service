package com.xjjk.knowledge.document.persistence;

import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.domain.SourceFile;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DocumentRepositoryIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    private static SqlSession sqlSession;
    private static DocumentRepository repository;

    @BeforeAll
    static void setUpRepository() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();

        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword());
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DocumentMapper.class);
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = sessionFactory.openSession(true);
        repository = new MybatisDocumentRepository(sqlSession.getMapper(DocumentMapper.class));
    }

    @AfterAll
    static void closeSession() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @Test
    void createsDraftAndKeepsPublishedPointerUntouched() {
        CreatedDocument created = repository.createDraft(
                1L,
                10L,
                10567L,
                "售后退款规则",
                source("refund-policy.pdf", "pdf"));

        assertThat(created.document().currentDraftVersionId()).isEqualTo(created.version().id());
        assertThat(created.document().currentPublishedVersionId()).isNull();
        assertThat(created.version().versionNumber()).isEqualTo(1);
        assertThat(created.version().sourceObjectKey())
                .isEqualTo("tenant/1/knowledge-base/10/document/"
                        + created.document().id()
                        + "/version/"
                        + created.version().id()
                        + "/source");
    }

    @Test
    void everyReadIsRestrictedToTenantAndActiveRows() {
        CreatedDocument tenantOne = repository.createDraft(
                21L, 200L, 10567L, "物流异常规范", source("logistics.docx", "docx"));
        repository.createDraft(
                22L, 200L, 20001L, "其他租户规范", source("other.txt", "txt"));

        assertThat(repository.findDocument(21L, tenantOne.document().id())).isPresent();
        assertThat(repository.findDocument(22L, tenantOne.document().id())).isEmpty();
        assertThat(repository.findVersion(
                22L, tenantOne.document().id(), tenantOne.version().id())).isEmpty();
        assertThat(repository.listDocuments(21L, 200L))
                .extracting(KnowledgeDocument::tenantId)
                .containsOnly(21L);
        assertThat(repository.listDocuments(22L, 200L))
                .extracting(KnowledgeDocument::tenantId)
                .containsOnly(22L);
    }

    private static SourceFile source(String filename, String extension) {
        return new SourceFile(
                filename,
                extension,
                "application/octet-stream",
                128L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }
}

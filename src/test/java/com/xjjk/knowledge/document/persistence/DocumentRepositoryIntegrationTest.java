package com.xjjk.knowledge.document.persistence;

import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.domain.SourceFile;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void createsUploadedVersionWithoutAdvancingDraftOrPublishedPointers() {
        CreatedDocument created = repository.createDocument(
                1L,
                10L,
                10567L,
                "售后退款规则",
                source("refund-policy.pdf", "pdf", 'a'),
                "repo-create-1");

        assertThat(created.document().currentDraftVersionId()).isNull();
        assertThat(created.document().currentPublishedVersionId()).isNull();
        assertThat(created.version().versionNumber()).isEqualTo(1);
        assertThat(created.version().sourceObjectKey())
                .isEqualTo("tenant/1/knowledge-base/10/document/"
                        + created.document().id()
                        + "/version/"
                        + created.version().id()
                        + "/source");
        assertThat((Long) sqlSession.selectOne(
                "com.xjjk.knowledge.document.persistence.DocumentMapper.countTasksForVersion",
                java.util.Map.of("tenantId", 1L, "versionId", created.version().id())))
                .isEqualTo(1L);
        assertThat((Long) sqlSession.selectOne(
                "com.xjjk.knowledge.document.persistence.DocumentMapper.countPendingOutboxForVersion",
                java.util.Map.of("tenantId", 1L, "versionId", created.version().id())))
                .isEqualTo(1L);
    }

    @Test
    void everyReadIsRestrictedToTenantAndActiveRows() {
        CreatedDocument tenantOne = repository.createDocument(
                21L, 200L, 10567L, "物流异常规范",
                source("logistics.docx", "docx", 'b'), "repo-tenant-1");
        repository.createDocument(
                22L, 200L, 20001L, "其他租户规范",
                source("other.txt", "txt", 'c'), "repo-tenant-2");

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

    @Test
    void createsSecondVersionWithoutCreatingAnotherDocument() {
        CreatedDocument first = repository.createDocument(
                31L, 300L, 10567L, "退款规则",
                source("refund-v1.pdf", "pdf", 'd'), "repo-version-1");

        CreatedDocument second = repository.createVersion(
                31L, 300L, first.document().id(), 10567L,
                source("refund-v2.pdf", "pdf", 'e'), "repo-version-2");

        assertThat(second.document().id()).isEqualTo(first.document().id());
        assertThat(second.version().versionNumber()).isEqualTo(2);
        assertThat(second.document().currentDraftVersionId()).isNull();
        assertThat(repository.listDocuments(31L, 300L)).hasSize(1);
        assertThat(repository.listVersions(31L, first.document().id()))
                .extracting(version -> version.versionNumber())
                .containsExactly(2, 1);
    }

    @Test
    void returnsExistingVersionForSameRequestAndRejectsChangedRequestPayload() {
        CreatedDocument first = repository.createDocument(
                41L, 400L, 10567L, "订单规则",
                source("order-v1.pdf", "pdf", 'f'), "repo-idempotent-1");

        CreatedDocument duplicate = repository.createVersion(
                41L, 400L, first.document().id(), 10567L,
                source("order-v2.pdf", "pdf", 'g'), "repo-idempotent-2");
        CreatedDocument retried = repository.createVersion(
                41L, 400L, first.document().id(), 10567L,
                source("order-v2.pdf", "pdf", 'g'), "repo-idempotent-2");

        assertThat(retried.version().id()).isEqualTo(duplicate.version().id());
        assertThatThrownBy(() -> repository.createVersion(
                41L, 400L, first.document().id(), 10567L,
                source("order-v3.pdf", "pdf", 'h'), "repo-idempotent-2"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void rejectsUnchangedContentUnderANewRequestId() {
        CreatedDocument first = repository.createDocument(
                51L, 500L, 10567L, "物流规则",
                source("logistics-v1.pdf", "pdf", 'i'), "repo-content-1");

        assertThatThrownBy(() -> repository.createVersion(
                51L, 500L, first.document().id(), 10567L,
                source("logistics-copy.pdf", "pdf", 'i'), "repo-content-2"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.DOCUMENT_CONTENT_UNCHANGED));
    }

    private static SourceFile source(String filename, String extension, char digestCharacter) {
        return new SourceFile(
                filename,
                extension,
                "application/octet-stream",
                128L,
                Character.toString(digestCharacter).repeat(64));
    }
}

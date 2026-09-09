package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.audit.AuditService;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.retrieval.indexing.PublicationIndexService;
import com.xjjk.knowledge.tenant.TenantAccessGuard;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers
class PublicationDisableConcurrencyIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge").withUsername("knowledge").withPassword("knowledge");

    @Test
    void concurrentDisableWithSameRequestIdReturnsTheSamePublicationRecord() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        SqlSessionFactory sessions = sessionFactory();
        seedPublishedDocument(sessions);

        CountDownLatch firstWriteFinished = new CountDownLatch(1);
        CountDownLatch secondInitialReadFinished = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<PublicationRecord> first = CompletableFuture.supplyAsync(() -> inTransaction(
                    sessions,
                    repository -> new DelegatingPublicationRepository(repository) {
                        @Override
                        public PublicationRecord disable(
                                PublicationTarget expected, long actorUserId, String requestId) {
                            PublicationRecord record = super.disable(expected, actorUserId, requestId);
                            firstWriteFinished.countDown();
                            await(allowFirstCommit);
                            return record;
                        }
                    }), executor);

            assertThat(firstWriteFinished.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<PublicationRecord> second = CompletableFuture.supplyAsync(() -> inTransaction(
                    sessions,
                    repository -> new DelegatingPublicationRepository(repository) {
                        private boolean firstRead = true;

                        @Override
                        public Optional<PublicationRecord> findByRequest(long tenantId, String requestId) {
                            Optional<PublicationRecord> result = super.findByRequest(tenantId, requestId);
                            if (firstRead) {
                                firstRead = false;
                                assertThat(result).isEmpty();
                                secondInitialReadFinished.countDown();
                            }
                            return result;
                        }
                    }), executor);

            assertThat(secondInitialReadFinished.await(5, TimeUnit.SECONDS)).isTrue();
            allowFirstCommit.countDown();

            PublicationRecord firstRecord = first.get(10, TimeUnit.SECONDS);
            PublicationRecord secondRecord = second.get(10, TimeUnit.SECONDS);
            assertThat(secondRecord.id()).isEqualTo(firstRecord.id());
            assertThat(secondRecord.requestId()).isEqualTo("same-disable-request");
        } finally {
            allowFirstCommit.countDown();
            executor.shutdownNow();
        }
    }

    private PublicationRecord inTransaction(
            SqlSessionFactory sessions,
            java.util.function.Function<PublicationRepository, PublicationRepository> decorate) {
        try (SqlSession session = sessions.openSession(false)) {
            try {
                session.getConnection().setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                PublicationRepository repository = decorate.apply(
                        new MybatisPublicationRepository(session.getMapper(PublicationMapper.class)));
                PublicationService service = new PublicationService(
                        new TenantAccessGuard(), repository,
                        mock(PublicationIndexService.class), mock(AuditService.class));
                PublicationRecord result = service.disable(
                        principal(), 1L, 2L, 3L, "same-disable-request");
                session.commit();
                return result;
            } catch (RuntimeException exception) {
                session.rollback();
                throw exception;
            } catch (Exception exception) {
                session.rollback();
                throw new IllegalStateException(exception);
            }
        }
    }

    private SqlSessionFactory sessionFactory() {
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PublicationMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void seedPublishedDocument(SqlSessionFactory sessions) throws Exception {
        try (SqlSession session = sessions.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO kb_knowledge_base(id,tenant_id,name,status,created_by,updated_by)
                    VALUES(2,1,'售后知识库','ENABLED',1,1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_document(id,tenant_id,knowledge_base_id,title,current_draft_version_id,
                                            current_published_version_id,created_by,updated_by,row_version)
                    VALUES(3,1,2,'退款规则',4,4,1,1,1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_document_version
                      (id,tenant_id,knowledge_base_id,document_id,version_number,status,original_filename,
                       file_extension,mime_type,file_size,source_sha256,source_object_key,embedding_model,
                       embedding_dimension,embedding_instruction_version,index_manifest_sha256,indexed_at,
                       unit_count,chunk_count,created_by)
                    VALUES(4,1,2,3,1,'PUBLISHED','refund.txt','txt','text/plain',10,
                       REPEAT('a',64),'key','qwen',2560,'instruction',REPEAT('b',64),CURRENT_TIMESTAMP(3),1,1,10567)
                    """);
        }
    }

    private AdminPrincipal principal() {
        return new AdminPrincipal(
                10567L, "74680", "石海文", 1L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试协调信号超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", exception);
        }
    }

    private static class DelegatingPublicationRepository implements PublicationRepository {
        private final PublicationRepository delegate;

        private DelegatingPublicationRepository(PublicationRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<PublicationRecord> findByRequest(long tenantId, String requestId) {
            return delegate.findByRequest(tenantId, requestId);
        }

        @Override
        public Optional<PublicationRecord> findByRequestForUpdate(long tenantId, String requestId) {
            return delegate.findByRequestForUpdate(tenantId, requestId);
        }

        @Override
        public PublicationTarget loadVersionTarget(
                long tenantId, long knowledgeBaseId, long documentId, long versionId) {
            return delegate.loadVersionTarget(tenantId, knowledgeBaseId, documentId, versionId);
        }

        @Override
        public PublicationTarget loadCurrentPublishedTarget(
                long tenantId, long knowledgeBaseId, long documentId) {
            return delegate.loadCurrentPublishedTarget(tenantId, knowledgeBaseId, documentId);
        }

        @Override
        public PublicationRecord activate(
                PublicationTarget expected, PublicationAction action, long actorUserId, String requestId) {
            return delegate.activate(expected, action, actorUserId, requestId);
        }

        @Override
        public PublicationRecord disable(
                PublicationTarget expected, long actorUserId, String requestId) {
            return delegate.disable(expected, actorUserId, requestId);
        }
    }
}

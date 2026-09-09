package com.xjjk.knowledge.document.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.SourceFile;
import com.xjjk.knowledge.document.persistence.DocumentMapper;
import com.xjjk.knowledge.document.persistence.MybatisDocumentRepository;
import java.time.Duration;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class IngestionTaskRepositoryIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge").withUsername("knowledge").withPassword("knowledge");

    private static SqlSession session;
    private static IngestionTaskRepository repository;
    private static long taskId;

    @BeforeAll
    static void setUp() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DocumentMapper.class);
        configuration.addMapper(IngestionTaskMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        CreatedDocument created = new MybatisDocumentRepository(session.getMapper(DocumentMapper.class)).createDraft(
                1L, 2L, 10567L, "退款规则",
                new SourceFile("refund.txt", "txt", "text/plain", 3L,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        taskId = session.getMapper(IngestionTaskMapper.class).findTaskId(1L, created.version().id());
        repository = new MybatisIngestionTaskRepository(session.getMapper(IngestionTaskMapper.class));
    }

    @AfterAll
    static void close() {
        if (session != null) session.close();
    }

    @Test
    void onlyOneWorkerClaimsAndExpiredLeaseCanBeRecovered() {
        IngestionTaskLease first = repository.claim(taskId, "worker-a", Duration.ofSeconds(30)).orElseThrow();

        assertThat(repository.claim(taskId, "worker-b", Duration.ofSeconds(30))).isEmpty();
        assertThat(repository.complete(taskId, "wrong-token")).isFalse();

        session.getMapper(IngestionTaskMapper.class).expireLease(taskId);
        IngestionTaskLease recovered = repository.claim(taskId, "worker-b", Duration.ofSeconds(30)).orElseThrow();
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(repository.complete(taskId, recovered.leaseToken())).isTrue();
        assertThat(repository.find(taskId).orElseThrow().status()).isEqualTo("DONE");
    }
}

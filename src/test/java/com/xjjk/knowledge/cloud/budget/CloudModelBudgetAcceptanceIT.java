package com.xjjk.knowledge.cloud.budget;

import com.xjjk.knowledge.cloud.client.BailianCallExecutor;
import com.xjjk.knowledge.cloud.client.BailianCallResult;
import com.xjjk.knowledge.cloud.config.BailianModelProperties;
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

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class CloudModelBudgetAcceptanceIT {
    private static final String BILLING_MONTH = "2026-09";
    private static final long HARD_LIMIT_MICROS = 180_000_000L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("knowledge_budget_acceptance");

    private static SqlSession session;

    @BeforeAll
    static void migrateAndSeedAlmostExhaustedBudget() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO knowledge_cloud_model_budget
                       (billing_month, hard_limit_micros, settled_micros, reserved_micros,
                        version, created_at, updated_at)
                     VALUES (?, ?, ?, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                     """)) {
            statement.setString(1, BILLING_MONTH);
            statement.setLong(2, HARD_LIMIT_MICROS);
            statement.setLong(3, HARD_LIMIT_MICROS - 1L);
            statement.executeUpdate();
        }

        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(new Environment(
                "acceptance", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(CloudModelBudgetMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
    }

    @AfterAll
    static void closeSession() {
        if (session != null) session.close();
    }

    @Test
    void hardStopRejectsBeforeProviderAndLeavesLedgerConsistent() throws Exception {
        BailianModelProperties properties = new BailianModelProperties();
        properties.setWorkspaceId("workspace-test");
        properties.setApiKey("test-key");
        CloudModelBudgetService budget = new CloudModelBudgetService(
                session.getMapper(CloudModelBudgetMapper.class), properties,
                new CloudModelCostEstimator(),
                Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneId.of("UTC")));
        BailianCallExecutor executor = new BailianCallExecutor(budget, properties);
        AtomicInteger providerInvocations = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(
                "acceptance-hard-stop", CloudModelCallType.EMBEDDING,
                properties.getEmbeddingModel(), 2L, () -> {
                    providerInvocations.incrementAndGet();
                    return new BailianCallResult<>("not-called", 1L, "provider-request");
                }))
                .isInstanceOf(CloudModelBudgetExceededException.class);

        assertThat(providerInvocations).hasValue(0);
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var budgetStatement = connection.prepareStatement("""
                     SELECT settled_micros, reserved_micros
                       FROM knowledge_cloud_model_budget WHERE billing_month = ?
                     """);
             var callStatement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM knowledge_cloud_model_call
                      WHERE logical_request_id = 'acceptance-hard-stop'
                     """)) {
            budgetStatement.setString(1, BILLING_MONTH);
            try (var result = budgetStatement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("settled_micros")).isEqualTo(HARD_LIMIT_MICROS - 1L);
                assertThat(result.getLong("reserved_micros")).isZero();
            }
            try (var result = callStatement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }
}

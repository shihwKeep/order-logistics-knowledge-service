package com.xjjk.knowledge.cloud.budget;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CloudModelBudgetConcurrencyIT {
    private static final long HARD_LIMIT = 180_000_000L;
    private static final long RESERVATION = 10_000_000L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("knowledge_budget_test");

    @BeforeAll
    static void migrateAndSeedMonth() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();
        try (var connection = connection();
             var statement = connection.prepareStatement("""
                     INSERT INTO knowledge_cloud_model_budget
                       (billing_month, hard_limit_micros, settled_micros, reserved_micros,
                        version, created_at, updated_at)
                     VALUES ('2026-09', ?, 0, 0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                     """)) {
            statement.setLong(1, HARD_LIMIT);
            statement.executeUpdate();
        }
    }

    @Test
    void parallelGuardedReservationsNeverExceedHardLimit() throws Exception {
        CountDownLatch ready = new CountDownLatch(40);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(40)) {
            List<Future<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try (var connection = connection();
                         var statement = connection.prepareStatement("""
                                 UPDATE knowledge_cloud_model_budget
                                    SET reserved_micros=reserved_micros+?, version=version+1,
                                        updated_at=CURRENT_TIMESTAMP(6)
                                  WHERE billing_month='2026-09'
                                    AND settled_micros+reserved_micros+?<=hard_limit_micros
                                 """)) {
                        statement.setLong(1, RESERVATION);
                        statement.setLong(2, RESERVATION);
                        return statement.executeUpdate();
                    }
                }));
            }
            ready.await();
            start.countDown();
            int succeeded = 0;
            for (Future<Integer> attempt : attempts) succeeded += attempt.get();
            assertThat(succeeded).isEqualTo(18);
        }

        try (var connection = connection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT settled_micros+reserved_micros AS total
                       FROM knowledge_cloud_model_budget WHERE billing_month='2026-09'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong("total")).isEqualTo(HARD_LIMIT);
        }
    }

    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}

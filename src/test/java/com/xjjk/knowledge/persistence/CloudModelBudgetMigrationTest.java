package com.xjjk.knowledge.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CloudModelBudgetMigrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("knowledge_test");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();
    }

    @Test
    void createsMonthlyAccountAndPhysicalAttemptLedger() throws Exception {
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            var tables = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "knowledge_cloud_model_%", new String[]{"TABLE"});
            var tableNames = new ArrayList<String>();
            while (tables.next()) tableNames.add(tables.getString("TABLE_NAME"));
            assertThat(tableNames)
                    .contains("knowledge_cloud_model_budget", "knowledge_cloud_model_call");

            var columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, "knowledge_cloud_model_budget", "%_micros");
            while (columns.next()) {
                assertThat(columns.getString("TYPE_NAME")).isEqualToIgnoringCase("BIGINT UNSIGNED");
            }
        }
    }
}

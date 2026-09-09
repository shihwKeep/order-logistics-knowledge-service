package com.xjjk.knowledge.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FoundationMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @Test
    void preservesKnowledgeBaseAndAuditTablesAfterAllMigrations() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();

        flyway.migrate();

        Set<String> tableNames = new HashSet<>();
        try (Connection connection = MYSQL.createConnection("");) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet tables = metadata.getTables(
                    MYSQL.getDatabaseName(), null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    tableNames.add(tables.getString("TABLE_NAME"));
                }
            }
        }

        assertThat(tableNames).contains(
                "flyway_schema_history",
                "kb_knowledge_base",
                "kb_audit_log");
        // 基础测试只关心 V1 始终存在；后续功能迁移不应导致这里随版本号反复修改。
        assertThat(Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().getVersion()))
                .contains("1");
    }
}

package com.xjjk.knowledge.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PublicationCleanupMigrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge").withUsername("knowledge").withPassword("knowledge");

    @Test
    void createsDurablePublishedIndexCleanupQueue() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load();
        flyway.migrate();

        try (Connection connection = MYSQL.createConnection("")) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertThat(tableNames(metadata)).contains("kb_published_index_cleanup");
        }
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
    }

    private Set<String> tableNames(DatabaseMetaData metadata) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getTables(MYSQL.getDatabaseName(), null, "%", new String[]{"TABLE"})) {
            while (rows.next()) names.add(rows.getString("TABLE_NAME"));
        }
        return names;
    }
}

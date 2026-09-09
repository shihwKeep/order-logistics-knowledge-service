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
class RetrievalPublicationMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @Test
    void createsRetrievalAndPublicationSchemaAtVersionThree() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();

        flyway.migrate();

        try (Connection connection = MYSQL.createConnection("")) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertThat(tableNames(metadata)).contains("kb_publish_record", "kb_search_log");
            assertThat(columnNames(metadata, "kb_document_version")).contains(
                    "embedding_model",
                    "embedding_dimension",
                    "embedding_instruction_version",
                    "index_manifest_sha256",
                    "indexed_at");
            assertThat(indexNames(metadata, "kb_publish_record")).contains(
                    "uk_publish_request", "idx_publish_tenant_document");
            assertThat(indexNames(metadata, "kb_search_log")).contains(
                    "idx_search_tenant_created");
        }

        assertThat(java.util.Arrays.stream(flyway.info().applied())
                .map(info -> info.getVersion().getVersion())).contains("3");
    }

    private Set<String> tableNames(DatabaseMetaData metadata) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getTables(MYSQL.getDatabaseName(), null, "%", new String[]{"TABLE"})) {
            while (rows.next()) {
                names.add(rows.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private Set<String> columnNames(DatabaseMetaData metadata, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getColumns(MYSQL.getDatabaseName(), null, table, "%")) {
            while (rows.next()) {
                names.add(rows.getString("COLUMN_NAME"));
            }
        }
        return names;
    }

    private Set<String> indexNames(DatabaseMetaData metadata, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getIndexInfo(MYSQL.getDatabaseName(), null, table, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}

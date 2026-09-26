package com.xjjk.knowledge.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class KnowledgeReleaseMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @Test
    void createsDocumentVersionAndReleaseSchema() throws Exception {
        Flyway flyway = cleanFlyway();
        flyway.migrate();

        try (Connection connection = MYSQL.createConnection("")) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertThat(tableNames(metadata)).contains(
                    "kb_release", "kb_release_item", "kb_release_task", "kb_derived_index_cleanup");
            assertThat(columnNames(metadata, "kb_document_version")).contains("upload_request_id");
            assertThat(columnNames(metadata, "kb_knowledge_base")).contains("current_release_id");
            assertThat(indexNames(metadata, "kb_document_version")).contains("uk_version_upload_request");
            assertThat(indexNames(metadata, "kb_release")).contains(
                    "uk_release_number", "uk_release_request");
            assertThat(indexNames(metadata, "kb_release_item")).contains("uk_release_document");
        }
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
    }

    @Test
    void backfillsExistingPublishedPointersIntoInitialRelease() throws Exception {
        Flyway v5 = Flyway.configure()
                .cleanDisabled(false)
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(MigrationVersion.fromVersion("5"))
                .load();
        v5.clean();
        v5.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO kb_knowledge_base
                      (id,tenant_id,name,status,created_by,updated_by,row_version)
                    VALUES (7,1,'售后知识库','ENABLED',9,9,3)
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_document
                      (id,tenant_id,knowledge_base_id,title,created_by,updated_by,row_version)
                    VALUES
                      (11,1,7,'退货规则',9,9,2),
                      (12,1,7,'退款规则',9,9,4)
                    """);
            statement.executeUpdate(versionInsert(91, 11, "退货规则.pdf", "a", "manifest-a"));
            statement.executeUpdate(versionInsert(92, 12, "退款规则.pdf", "b", "manifest-b"));
            statement.executeUpdate("""
                    UPDATE kb_document
                       SET current_draft_version_id=CASE id WHEN 11 THEN 91 ELSE 92 END,
                           current_published_version_id=CASE id WHEN 11 THEN 91 ELSE 92 END
                     WHERE id IN (11,12)
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_publish_record
                      (id,tenant_id,knowledge_base_id,document_id,from_version_id,to_version_id,
                       action,actor_user_id,request_id,chunk_count,manifest_sha256)
                    VALUES (31,1,7,11,NULL,91,'PUBLISH',9,'legacy-publish',1,'manifest-a')
                    """);
            statement.executeUpdate("""
                    INSERT INTO kb_published_index_cleanup
                      (tenant_id,publish_record_id,document_id,version_id,status)
                    VALUES (1,31,11,90,'PENDING')
                    """);
        }

        Flyway latest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();
        latest.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            long releaseId;
            try (ResultSet row = statement.executeQuery(
                    "SELECT current_release_id FROM kb_knowledge_base WHERE id=7")) {
                assertThat(row.next()).isTrue();
                releaseId = row.getLong(1);
                assertThat(row.wasNull()).isFalse();
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT status,request_id FROM kb_release WHERE id=" + releaseId)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("ACTIVE");
                assertThat(row.getString("request_id")).isEqualTo("legacy-release-7");
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT document_id,version_id FROM kb_release_item WHERE release_id="
                            + releaseId + " ORDER BY document_id")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong("document_id")).isEqualTo(11);
                assertThat(row.getLong("version_id")).isEqualTo(91);
                assertThat(row.next()).isTrue();
                assertThat(row.getLong("document_id")).isEqualTo(12);
                assertThat(row.getLong("version_id")).isEqualTo(92);
                assertThat(row.next()).isFalse();
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT upload_request_id FROM kb_document_version ORDER BY id")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("legacy-version-91");
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("legacy-version-92");
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT index_layer,cleanup_reason FROM kb_derived_index_cleanup WHERE version_id=90")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("index_layer")).isEqualTo("PUBLISHED");
                assertThat(row.getString("cleanup_reason")).isEqualTo("RELEASE_SUPERSEDED");
            }
        }
    }

    private Flyway cleanFlyway() {
        Flyway flyway = Flyway.configure()
                .cleanDisabled(false)
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();
        flyway.clean();
        return flyway;
    }

    private String versionInsert(long id, long documentId, String filename, String shaSuffix, String manifest) {
        return """
                INSERT INTO kb_document_version
                  (id,tenant_id,knowledge_base_id,document_id,version_number,status,
                   original_filename,file_extension,mime_type,file_size,source_sha256,
                   source_object_key,index_manifest_sha256,chunk_count,created_by)
                VALUES (%d,1,7,%d,1,'PUBLISHED','%s','pdf','application/pdf',10,
                        '%s','tenant/1/version/%d/source','%s',1,9)
                """.formatted(id, documentId, filename, shaSuffix.repeat(64), id, manifest);
    }

    private Set<String> tableNames(DatabaseMetaData metadata) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getTables(MYSQL.getDatabaseName(), null, "%", new String[]{"TABLE"})) {
            while (rows.next()) names.add(rows.getString("TABLE_NAME"));
        }
        return names;
    }

    private Set<String> columnNames(DatabaseMetaData metadata, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getColumns(MYSQL.getDatabaseName(), null, table, "%")) {
            while (rows.next()) names.add(rows.getString("COLUMN_NAME"));
        }
        return names;
    }

    private Set<String> indexNames(DatabaseMetaData metadata, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rows = metadata.getIndexInfo(MYSQL.getDatabaseName(), null, table, false, false)) {
            while (rows.next()) {
                if (rows.getString("INDEX_NAME") != null) names.add(rows.getString("INDEX_NAME"));
            }
        }
        return names;
    }
}

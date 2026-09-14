package com.xjjk.knowledge.retrieval.indexing;

import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.xjjk.knowledge.retrieval.embedding.EmbeddingProperties;

import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class IndexRecoveryRepositoryIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_logistics_knowledge")
            .withUsername("knowledge")
            .withPassword("knowledge");

    @Test
    void selectsOnlyCurrentRecoverablePointersAndMapsCompleteVersions() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load().migrate();
        PooledDataSource dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(IndexRecoveryMapper.class);
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO kb_knowledge_base(id,tenant_id,name,status,created_by,updated_by)
                        VALUES(2,1,'售后知识库','ENABLED',1,1)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document_version(
                          id,tenant_id,knowledge_base_id,document_id,version_number,status,
                          original_filename,file_extension,mime_type,file_size,source_sha256,
                          source_object_key,parser_version,chunk_strategy_version,embedding_model,
                          embedding_dimension,embedding_instruction_version,index_manifest_sha256,
                          unit_count,chunk_count,created_by)
                        VALUES
                          (4,1,2,3,1,'PUBLISHED','v1.txt','txt','text/plain',10,REPEAT('a',64),
                           'source/4','text-v1','structural-v1','qwen3.7-text-embedding',2560,
                           'qwen3-customer-service-v1',REPEAT('b',64),1,1,10567),
                          (5,1,2,3,2,'READY','v2.txt','txt','text/plain',10,REPEAT('c',64),
                           'source/5','text-v1','structural-v1','qwen3.7-text-embedding',2560,
                           'qwen3-customer-service-v1',REPEAT('d',64),1,1,10567),
                          (6,1,2,3,3,'READY','old.txt','txt','text/plain',10,REPEAT('e',64),
                           'source/6','text-v1','structural-v1','qwen3.7-text-embedding',2560,
                           'qwen3-customer-service-v1',REPEAT('f',64),1,1,10567)
                        """);
                statement.executeUpdate("""
                        INSERT INTO kb_document(
                          id,tenant_id,knowledge_base_id,title,current_draft_version_id,
                          current_published_version_id,created_by,updated_by)
                        VALUES(3,1,2,'退款规则',5,4,10567,10567)
                        """);
            }

            EmbeddingProperties embedding = new EmbeddingProperties();
            embedding.setModel("qwen3.7-text-embedding");
            embedding.setDimension(2560);
            embedding.setInstructionVersion("qwen37-customer-service-v2");
            MybatisIndexRecoveryRepository repository = new MybatisIndexRecoveryRepository(
                    session.getMapper(IndexRecoveryMapper.class), embedding);

            var targets = repository.listCurrentTargets();
            assertThat(targets)
                    .extracting(target -> target.layer().name() + ":" + target.version().id())
                    .containsExactly("PUBLISHED:4", "DRAFT:5");

            assertThat(repository.upgradeEmbeddingContract(targets.getFirst().version())).isTrue();
            assertThat(repository.upgradeEmbeddingContract(targets.getFirst().version())).isFalse();
            try (var statement = session.getConnection().prepareStatement("""
                    SELECT embedding_model, embedding_dimension, embedding_instruction_version
                      FROM kb_document_version WHERE id=4
                    """); var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("embedding_model")).isEqualTo("qwen3.7-text-embedding");
                assertThat(result.getInt("embedding_dimension")).isEqualTo(2560);
                assertThat(result.getString("embedding_instruction_version"))
                        .isEqualTo("qwen37-customer-service-v2");
            }
        }
    }
}

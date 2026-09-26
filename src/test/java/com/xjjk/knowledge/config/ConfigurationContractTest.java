package com.xjjk.knowledge.config;

import com.xjjk.knowledge.cloud.config.BailianModelProperties;
import com.xjjk.knowledge.retrieval.service.RetrievalProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationContractTest {

    @Test
    void localConfigurationUsesEnvironmentSecretAndDocumentsStartupContract() throws Exception {
        String localYaml = Files.readString(Path.of("src/main/resources/application-local.yml"));
        String baseYaml = Files.readString(Path.of("src/main/resources/application.yml"));
        Path runbookPath = Path.of("docs/local-foundation-runbook.md");

        assertThat(localYaml).contains("client-secret: ${SSPX_CLIENT_SECRET}");
        assertThat(localYaml).doesNotMatch(
                "(?s).*client-secret:\\s*[A-Fa-f0-9]{32,}.*");
        assertThat(baseYaml).contains("port: ${KNOWLEDGE_SERVER_PORT:8084}");
        assertThat(baseYaml)
                .containsPattern("(?s)user-memory:\\s+enabled: false")
                .contains("index-alias: agent-user-memory-active")
                .contains("index-name: agent-user-memory-v2")
                .contains("collection: agent_user_memory_v2")
                .contains("es-top-k: 20")
                .contains("milvus-top-k: 20")
                .contains("final-top-k: 10")
                .contains("timeout: 3s")
                .contains("strategy-version: user-memory-es-milvus-rrf60-qwen37-v2");
        assertThat(localYaml)
                .contains("base-url: ${SSPX_BASE_URL:http://127.0.0.1:9092}")
                .contains("endpoint: ${KNOWLEDGE_MINIO_ENDPOINT:http://127.0.0.1:9000}")
                .contains("access-key: ${KNOWLEDGE_MINIO_ACCESS_KEY:${KNOWLEDGE_MINIO_ROOT_USER}}")
                .contains("secret-key: ${KNOWLEDGE_MINIO_SECRET_KEY:${KNOWLEDGE_MINIO_ROOT_PASSWORD}}")
                .contains("password: ${KNOWLEDGE_RABBITMQ_PASSWORD}")
                .contains("base-url: ${KNOWLEDGE_OCR_BASE_URL:http://127.0.0.1:8091}")
                .contains("max-file-size: 100MB")
                .contains("lease-duration: 60s")
                .contains("target-tokens: 500")
                .doesNotMatch("(?s).*internal-api:\\s*.*secret:\\s*[A-Fa-f0-9]{32,}.*");
        assertThat(runbookPath).exists();

        String runbook = Files.readString(runbookPath);
        assertThat(runbook)
                .contains("CREATE DATABASE IF NOT EXISTS order_logistics_knowledge")
                .contains("Read-Host -MaskInput")
                .contains("Path: /knowledge/**")
                .contains("Target: http://127.0.0.1:8084")
                .doesNotContain("client_secret=");
    }

    @Test
    void localRetrievalLimitsCpuRerankingAndAllowsMeasuredInferenceTime() throws Exception {
        String localYaml = Files.readString(Path.of("src/main/resources/application-local.yml"));
        String baseYaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(baseYaml)
                .contains("workspace-id: ${KNOWLEDGE_DASHSCOPE_WORKSPACE_ID}")
                .contains("api-key: ${KNOWLEDGE_DASHSCOPE_API_KEY}")
                .contains("hard-limit-micros: 180000000");
        assertThat(localYaml)
                .contains("fusion-top-k: 10")
                .contains("draft-index: knowledge_chunks_draft_v2")
                .contains("published-index: knowledge_chunks_published_v2")
                .contains("version: qwen37-es-milvus-rrf60-rerank-v3")
                .doesNotContain("11434")
                .doesNotContain("127.0.0.1:8000")
                .doesNotContain("strict-rrf-threshold");
    }

    @Test
    void retrievalPropertyDefaultsMatchLocalCpuBudget() {
        assertThat(new RetrievalProperties().getFusionTopK()).isEqualTo(10);
        assertThat(new RetrievalProperties().getVersion()).isEqualTo("qwen37-es-milvus-rrf60-rerank-v3");
        assertThat(new BailianModelProperties().getReadTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
    }

    @Test
    void releaseScopeFilterBatchMustBePositive() {
        RetrievalProperties properties = new RetrievalProperties();
        assertThat(properties.getReleaseFilterBatchSize()).isEqualTo(200);

        properties.setReleaseFilterBatchSize(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("知识检索策略配置不合法");
    }
}

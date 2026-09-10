package com.xjjk.knowledge.config;

import com.xjjk.knowledge.retrieval.rerank.RerankerProperties;
import com.xjjk.knowledge.retrieval.service.RetrievalProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(localYaml)
                .contains("fusion-top-k: 10")
                .containsPattern("(?s)reranker:.*?read-timeout: 12s")
                .contains("version: qwen3-es-milvus-rrf60-bge-v2")
                .doesNotContain("strict-rrf-threshold");
    }

    @Test
    void retrievalPropertyDefaultsMatchLocalCpuBudget() {
        assertThat(new RetrievalProperties().getFusionTopK()).isEqualTo(10);
        assertThat(new RetrievalProperties().getVersion()).isEqualTo("qwen3-es-milvus-rrf60-bge-v2");
        assertThat(new RerankerProperties().getReadTimeout()).isEqualTo(java.time.Duration.ofSeconds(12));
    }
}

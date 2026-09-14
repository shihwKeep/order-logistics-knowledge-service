package com.xjjk.knowledge.cloud.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BailianModelPropertiesTest {

    @Test
    void acceptsProductionDefaults() {
        BailianModelProperties properties = validProperties();

        assertThatNoException().isThrownBy(properties::validate);
        assertThat(properties.embeddingEndpoint().toString()).endsWith(
                "/api/v1/services/embeddings/text-embedding/text-embedding");
        assertThat(properties.rerankEndpoint().toString()).endsWith(
                "/api/v1/services/rerank/text-rerank/text-rerank");
        assertThat(properties.getMonthlyBudgetMicros()).isEqualTo(200_000_000L);
        assertThat(properties.getHardLimitMicros()).isEqualTo(180_000_000L);
    }

    @Test
    void rejectsMissingCredentials() {
        BailianModelProperties properties = validProperties();
        properties.setApiKey(" ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void rejectsHardLimitAboveMonthlyBudget() {
        BailianModelProperties properties = validProperties();
        properties.setHardLimitMicros(200_000_001L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预算");
    }

    @Test
    void rejectsUnsupportedRegionBatchAndRetryLimits() {
        BailianModelProperties properties = validProperties();
        properties.setRegion("ap-southeast-1");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);

        properties = validProperties();
        properties.setEmbeddingBatchSize(21);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);

        properties = validProperties();
        properties.setMaxAttempts(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    private BailianModelProperties validProperties() {
        BailianModelProperties properties = new BailianModelProperties();
        properties.setWorkspaceId("ws-test");
        properties.setApiKey("test-key");
        return properties;
    }
}

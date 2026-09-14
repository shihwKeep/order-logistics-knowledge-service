package com.xjjk.knowledge.cloud.budget;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CloudModelCostEstimatorTest {
    private final CloudModelCostEstimator estimator = new CloudModelCostEstimator();

    @Test
    void roundsUpMicroYuanAndUsesUtf8AsTokenUpperBound() {
        assertThat(estimator.embeddingMaximumCharge(List.of("退款"), "指令", 500_000L))
                .isEqualTo(6L);
    }

    @Test
    void rerankCountsQueryOncePerDocument() {
        assertThat(estimator.rerankMaximumCharge("问", List.of("甲", "乙"), "指令", 500_000L))
                .isEqualTo(9L);
    }

    @Test
    void settlesFromProviderTokens() {
        assertThat(estimator.actualCharge(79L, 500_000L)).isEqualTo(40L);
    }
}

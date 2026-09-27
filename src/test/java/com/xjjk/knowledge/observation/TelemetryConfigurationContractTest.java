package com.xjjk.knowledge.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TelemetryConfigurationContractTest {

    @Test
    void shouldExposePrometheusAndConfigureOtlpTracing() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml)
                    .contains("include: health,info,prometheus")
                    .contains("sampling:")
                    .contains("probability: ${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}")
                    .contains("endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://127.0.0.1:4318/v1/traces}");
        }
    }
}

package com.xjjk.knowledge.retrieval.embedding;

import com.sun.net.httpserver.HttpServer;
import com.xjjk.knowledge.cloud.budget.BudgetReservation;
import com.xjjk.knowledge.cloud.budget.CloudModelBudgetService;
import com.xjjk.knowledge.cloud.budget.CloudModelCallType;
import com.xjjk.knowledge.cloud.budget.CloudModelCostEstimator;
import com.xjjk.knowledge.cloud.client.BailianCallExecutor;
import com.xjjk.knowledge.cloud.config.BailianModelProperties;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BailianEmbeddingClientTest {
    @Test
    void sendsNativeDocumentContractAndMapsVectorsByIndex() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(body, authorization,
                "{\"output\":{\"embeddings\":["
                        + "{\"embedding\":[0.1,0.2,0.3,0.4],\"text_index\":1},"
                        + "{\"embedding\":[0.5,0.6,0.7,0.8],\"text_index\":0}]},"
                        + "\"usage\":{\"total_tokens\":12},\"request_id\":\"provider-1\"}");
        try {
            BailianEmbeddingClient client = client(server);

            List<List<Float>> vectors = client.embedDocuments(List.of("退款规则", "物流规范"));

            assertThat(vectors.get(0)).containsExactly(0.5F, 0.6F, 0.7F, 0.8F);
            assertThat(vectors.get(1)).containsExactly(0.1F, 0.2F, 0.3F, 0.4F);
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            assertThat(body.get()).contains("\"model\":\"qwen3.7-text-embedding\"")
                    .contains("\"texts\":[\"退款规则\",\"物流规范\"]")
                    .contains("\"text_type\":\"document\"")
                    .contains("\"dimension\":4")
                    .contains("\"output_type\":\"dense\"")
                    .doesNotContain("\"instruct\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsQueryRoleAndInstruction() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(body, new AtomicReference<>(),
                "{\"output\":{\"embeddings\":[{\"embedding\":[0.1,0.2,0.3,0.4],\"text_index\":0}]},"
                        + "\"usage\":{\"total_tokens\":8},\"request_id\":\"provider-2\"}");
        try {
            assertThat(client(server).embedQuery("怎么退款")).hasSize(4);
            assertThat(body.get()).contains("\"text_type\":\"query\"")
                    .contains("Given a Chinese customer-service question")
                    .doesNotContain("Represent this query");
        } finally {
            server.stop(0);
        }
    }

    private BailianEmbeddingClient client(HttpServer server) {
        BailianModelProperties properties = new BailianModelProperties();
        properties.setWorkspaceId("ws-test");
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setEmbeddingDimension(4);
        properties.setMaxAttempts(1);
        CloudModelBudgetService budget = mock(CloudModelBudgetService.class);
        when(budget.reserve(anyString(), eq(CloudModelCallType.EMBEDDING), eq(1),
                eq("qwen3.7-text-embedding"), anyLong()))
                .thenReturn(new BudgetReservation("call-1", "2026-09", 1_000L,
                        CloudModelCallType.EMBEDDING, 1));
        return new BailianEmbeddingClient(properties,
                new BailianCallExecutor(budget, properties), new CloudModelCostEstimator());
    }

    private HttpServer server(AtomicReference<String> body, AtomicReference<String> authorization,
                              String response) throws Exception {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/embeddings/text-embedding/text-embedding", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
}

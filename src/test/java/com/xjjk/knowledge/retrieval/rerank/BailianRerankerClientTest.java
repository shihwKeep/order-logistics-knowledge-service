package com.xjjk.knowledge.retrieval.rerank;

import com.sun.net.httpserver.HttpServer;
import com.xjjk.knowledge.cloud.budget.BudgetReservation;
import com.xjjk.knowledge.cloud.budget.CloudModelBudgetService;
import com.xjjk.knowledge.cloud.budget.CloudModelCallType;
import com.xjjk.knowledge.cloud.budget.CloudModelCostEstimator;
import com.xjjk.knowledge.cloud.client.BailianCallExecutor;
import com.xjjk.knowledge.cloud.config.BailianModelProperties;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import com.xjjk.knowledge.retrieval.model.RecallSource;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BailianRerankerClientTest {
    @Test
    void sendsNativeContractAndMapsIndexesBackToCandidates() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(body,
                "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.91},"
                        + "{\"index\":0,\"relevance_score\":0.42}]},"
                        + "\"usage\":{\"prompt_tokens\":79,\"total_tokens\":79},"
                        + "\"request_id\":\"provider-r1\"}");
        try {
            List<RankedEvidence> result = client(server).rerank(
                    "怎么退款", List.of(evidence("a"), evidence("b")));

            assertThat(result).extracting(item -> item.chunk().chunkId()).containsExactly("b", "a");
            assertThat(result).extracting(RankedEvidence::score).containsExactly(0.91D, 0.42D);
            assertThat(body.get()).contains("\"model\":\"qwen3.7-text-rerank\"")
                    .contains("\"query\":\"怎么退款\"")
                    .contains("\"documents\":[\"正文-a\",\"正文-b\"]")
                    .contains("\"top_n\":2")
                    .contains("Given a Chinese customer-service question");
        } finally {
            server.stop(0);
        }
    }

    private BailianRerankerClient client(HttpServer server) {
        BailianModelProperties cloud = new BailianModelProperties();
        cloud.setWorkspaceId("ws-test");
        cloud.setApiKey("test-key");
        cloud.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        cloud.setMaxAttempts(1);
        CloudModelBudgetService budget = mock(CloudModelBudgetService.class);
        when(budget.reserve(anyString(), eq(CloudModelCallType.RERANK), eq(1),
                eq("qwen3.7-text-rerank"), anyLong()))
                .thenReturn(new BudgetReservation("call-r1", "2026-09", 1_000L,
                        CloudModelCallType.RERANK, 1));
        RerankerProperties reranker = new RerankerProperties();
        return new BailianRerankerClient(cloud, reranker,
                new BailianCallExecutor(budget, cloud), new CloudModelCostEstimator());
    }

    private HttpServer server(AtomicReference<String> body, String response) throws Exception {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/rerank/text-rerank/text-rerank", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private RankedEvidence evidence(String id) {
        return new RankedEvidence(new IndexChunk(
                id, 1L, 2L, 3L, 4L, 0, "退款规则", "售后", "正文-" + id, "hash-" + id, "{}"),
                0.02D, 0.02D, Set.of(RecallSource.VECTOR), 1);
    }
}

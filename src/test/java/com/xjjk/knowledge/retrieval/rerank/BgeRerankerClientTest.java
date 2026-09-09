package com.xjjk.knowledge.retrieval.rerank;

import com.sun.net.httpserver.HttpServer;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BgeRerankerClientTest {

    @Test
    void postsExistingBgeContractAndMapsIndexesBackToCandidates() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(requestBody,
                "{\"results\":[{\"index\":1,\"relevanceScore\":0.91},{\"index\":0,\"relevanceScore\":0.42}]}");
        try {
            BgeRerankerClient client = new BgeRerankerClient(properties(server));

            List<RankedEvidence> result = client.rerank("怎么退款", List.of(evidence("a"), evidence("b")));

            assertThat(requestBody.get())
                    .contains("\"query\":\"怎么退款\"")
                    .contains("\"documents\":[\"正文-a\",\"正文-b\"]")
                    .contains("\"topK\":2");
            assertThat(result).extracting(item -> item.chunk().chunkId()).containsExactly("b", "a");
            assertThat(result).extracting(RankedEvidence::score).containsExactly(0.91D, 0.42D);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsDuplicateOrOutOfRangeIndexesAndIllegalScores() throws Exception {
        HttpServer server = server(new AtomicReference<>(),
                "{\"results\":[{\"index\":0,\"relevanceScore\":0.8},{\"index\":0,\"relevanceScore\":1.2}]}");
        try {
            BgeRerankerClient client = new BgeRerankerClient(properties(server));

            assertThatThrownBy(() -> client.rerank("退款", List.of(evidence("a"))))
                    .isInstanceOf(RerankerUnavailableException.class)
                    .hasMessageContaining("不合法");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(AtomicReference<String> body, String response) throws Exception {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rerank", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private RerankerProperties properties(HttpServer server) {
        RerankerProperties properties = new RerankerProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTopK(5);
        return properties;
    }

    private RankedEvidence evidence(String id) {
        IndexChunk chunk = new IndexChunk(
                id, 1L, 2L, 3L, 4L, 0, "退款规则", "售后", "正文-" + id, "hash-" + id, "{}");
        return new RankedEvidence(chunk, 0.02D, 0.02D, Set.of(RecallSource.VECTOR), 1);
    }
}

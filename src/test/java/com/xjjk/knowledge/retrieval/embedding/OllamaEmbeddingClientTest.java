package com.xjjk.knowledge.retrieval.embedding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaEmbeddingClientTest {

    @Test
    void sendsQwenBatchContractAndValidatesDimensions() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(body, response(2, 4));
        try {
            EmbeddingProperties properties = properties(server, 4);
            OllamaEmbeddingClient client = new OllamaEmbeddingClient(properties);

            List<List<Float>> vectors = client.embedDocuments(List.of("退款规则", "物流规范"));

            assertThat(vectors).hasSize(2).allSatisfy(vector -> assertThat(vector).hasSize(4));
            assertThat(body.get())
                    .contains("\"model\":\"qwen3-embedding:4b-q4_K_M\"")
                    .contains("\"truncate\":false")
                    .contains("\"dimensions\":4")
                    .contains("Represent this document for retrieval");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesDistinctQueryInstructionAndRejectsWrongVectorShape() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(body, response(1, 3));
        try {
            OllamaEmbeddingClient client = new OllamaEmbeddingClient(properties(server, 4));

            assertThatThrownBy(() -> client.embedQuery("签收后几天能退款"))
                    .isInstanceOf(EmbeddingUnavailableException.class)
                    .hasMessageContaining("维度");
            assertThat(body.get()).contains("Represent this query for retrieving relevant documents");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsOllamaRuntimeMetadataFields() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String response = new String(response(1, 4), StandardCharsets.UTF_8);
        response = response.substring(0, response.length() - 1)
                + ",\"total_duration\":343000000,\"load_duration\":12000000,\"prompt_eval_count\":18}";
        HttpServer server = server(body, response.getBytes(StandardCharsets.UTF_8));
        try {
            OllamaEmbeddingClient client = new OllamaEmbeddingClient(properties(server, 4));

            assertThat(client.embedQuery("员工手册")).hasSize(4);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(AtomicReference<String> body, byte[] response) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private EmbeddingProperties properties(HttpServer server, int dimension) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("qwen3-embedding:4b-q4_K_M");
        properties.setDimension(dimension);
        properties.setBatchSize(8);
        return properties;
    }

    private byte[] response(int count, int dimension) {
        String vector = "[" + String.join(",", java.util.Collections.nCopies(dimension, "0.25")) + "]";
        String json = "{\"model\":\"qwen3-embedding:4b-q4_K_M\",\"embeddings\":["
                + String.join(",", java.util.Collections.nCopies(count, vector)) + "]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}

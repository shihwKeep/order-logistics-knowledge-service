package com.xjjk.knowledge.retrieval.index;

import com.sun.net.httpserver.HttpServer;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchKeywordIndexTest {

    @Test
    void createsIkIndexesAndUsesStableBulkIdsAndTenantFilter() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String request = exchange.getRequestMethod() + " " + exchange.getRequestURI() + "\n"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            String path = exchange.getRequestURI().getPath();
            int status = "HEAD".equals(exchange.getRequestMethod()) ? 404 : 200;
            String json;
            if (path.endsWith("/_search")) {
                json = "{\"hits\":{\"hits\":[{\"_score\":3.5,\"_source\":{" +
                        "\"chunkId\":\"1-3-4-0\",\"tenantId\":1,\"knowledgeBaseId\":2," +
                        "\"documentId\":3,\"versionId\":4,\"chunkIndex\":0," +
                        "\"documentTitle\":\"退款规则\",\"titlePath\":\"售后\"," +
                        "\"content\":\"签收后七日内可申请退款\",\"contentSha256\":\"abc\"," +
                        "\"locationJson\":\"{\\\"pageNumber\\\":3}\"}}]}}";
            } else if (path.equals("/_bulk")) {
                json = "{\"errors\":false,\"items\":[]}";
            } else {
                json = "{\"acknowledged\":true,\"deleted\":1}";
            }
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ElasticsearchProperties properties = new ElasticsearchProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            ElasticsearchKeywordIndex index = new ElasticsearchKeywordIndex(properties);
            IndexChunk chunk = new IndexChunk(
                    "1-3-4-0", 1L, 2L, 3L, 4L, 0, "退款规则", "售后",
                    "签收后七日内可申请退款", "abc", "{\"pageNumber\":3}");

            index.ensureReady();
            index.replaceVersion(IndexLayer.DRAFT, List.of(chunk));
            var hits = index.search(IndexLayer.PUBLISHED, 1L, List.of(2L), "退款期限", 30);

            assertThat(hits).singleElement().satisfies(hit -> {
                assertThat(hit.chunk().chunkId()).isEqualTo("1-3-4-0");
                assertThat(hit.score()).isEqualTo(3.5);
            });
            assertThat(requests.stream().filter(value -> value.startsWith("PUT ")).toList())
                    .hasSize(2)
                    .allSatisfy(value -> assertThat(value)
                            .contains("ik_max_word")
                            .contains("ik_smart"));
            assertThat(requests).anySatisfy(value -> assertThat(value)
                    .startsWith("POST /_bulk")
                    .contains("\"_id\":\"1-3-4-0\"")
                    .contains("knowledge_chunks_draft_v1"));
            assertThat(requests).anySatisfy(value -> assertThat(value)
                    .startsWith("POST /knowledge_chunks_published_v1/_search")
                    .contains("\"tenantId\":1")
                    .contains("\"knowledgeBaseId\"")
                    .contains("退款期限"));
        } finally {
            server.stop(0);
        }
    }
}

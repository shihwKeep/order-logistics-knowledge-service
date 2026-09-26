package com.xjjk.knowledge.retrieval.index;

import com.sun.net.httpserver.HttpServer;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.DocumentVersionRef;
import com.xjjk.knowledge.retrieval.service.RetrievalProperties;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchKeywordIndexTest {

    @Test
    void filtersPublishedSearchByExactVersionPairsAndMergesBatches() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        AtomicInteger searchCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + "\n" + body);
            int call = searchCalls.incrementAndGet();
            String json = call == 1
                    ? searchHits(hit("shared", 0.70D, 3L, 11L), hit("first", 0.60D, 4L, 12L))
                    : searchHits(hit("shared", 0.95D, 3L, 11L), hit("second", 0.80D, 5L, 13L));
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ElasticsearchProperties elasticsearch = new ElasticsearchProperties();
            elasticsearch.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            RetrievalProperties retrieval = new RetrievalProperties();
            retrieval.setReleaseFilterBatchSize(2);
            ElasticsearchKeywordIndex index = new ElasticsearchKeywordIndex(elasticsearch, retrieval);
            List<DocumentVersionRef> versions = List.of(
                    new DocumentVersionRef(2L, 3L, 11L),
                    new DocumentVersionRef(2L, 4L, 12L),
                    new DocumentVersionRef(2L, 5L, 13L));

            var hits = index.search(
                    IndexLayer.PUBLISHED, 1L, List.of(2L), versions, "退款期限", 2);

            assertThat(hits).extracting(hit -> hit.chunk().chunkId())
                    .containsExactly("shared", "second");
            assertThat(hits).extracting(hit -> hit.score()).containsExactly(0.95D, 0.80D);
            assertThat(requests).hasSize(2);
            assertThat(requests.getFirst())
                    .contains("\"tenantId\":1")
                    .contains("\"knowledgeBaseId\"")
                    .contains("\"minimum_should_match\":1")
                    .contains("\"documentId\":3")
                    .contains("\"versionId\":11")
                    .contains("\"documentId\":4")
                    .contains("\"versionId\":12")
                    .doesNotContain("\"documentId\":[3,4]")
                    .doesNotContain("\"versionId\":[11,12]");
            assertThat(requests.get(1))
                    .contains("\"documentId\":5")
                    .contains("\"versionId\":13");
        } finally {
            server.stop(0);
        }
    }

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
            ElasticsearchKeywordIndex index = new ElasticsearchKeywordIndex(
                    properties, new RetrievalProperties());
            IndexChunk chunk = new IndexChunk(
                    "1-3-4-0", 1L, 2L, 3L, 4L, 0, "退款规则", "退款规范 > 5 优惠处理",
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
                    .startsWith("POST /_bulk?refresh=wait_for")
                    .contains("\"_id\":\"1-3-4-0\"")
                    .contains("\"titlePath\":\"退款规范 > 5 优惠处理\"")
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

    private static String searchHits(String... hits) {
        return "{\"hits\":{\"hits\":[" + String.join(",", hits) + "]}}";
    }

    private static String hit(String chunkId, double score, long documentId, long versionId) {
        return "{\"_score\":" + score + ",\"_source\":{" +
                "\"chunkId\":\"" + chunkId + "\",\"tenantId\":1,\"knowledgeBaseId\":2," +
                "\"documentId\":" + documentId + ",\"versionId\":" + versionId + "," +
                "\"chunkIndex\":0,\"documentTitle\":\"退款规则\",\"titlePath\":\"售后\"," +
                "\"content\":\"正文\",\"contentSha256\":\"abc\",\"locationJson\":\"{}\"}}";
    }
}

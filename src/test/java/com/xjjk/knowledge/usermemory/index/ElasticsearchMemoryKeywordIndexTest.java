package com.xjjk.knowledge.usermemory.index;

import com.sun.net.httpserver.HttpServer;
import com.xjjk.knowledge.retrieval.index.ElasticsearchProperties;
import com.xjjk.knowledge.usermemory.config.UserMemoryIndexProperties;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchMemoryKeywordIndexTest {

    @Test
    void usesIsolatedAliasAndAlwaysFiltersOwnerGenerationAndExpiry() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + "\n" + body);
            String path = exchange.getRequestURI().getPath();
            int status = "HEAD".equals(exchange.getRequestMethod()) ? 404 : 200;
            String json = path.endsWith("/_search")
                    ? "{\"hits\":{\"hits\":[{\"_score\":4.2,\"_source\":{" +
                    "\"memory_id\":\"m-1\",\"tenant_id\":1,\"user_id\":74680," +
                    "\"memory_generation\":3,\"memory_version\":2," +
                    "\"source_type\":\"AUTO_EXTRACT\",\"category\":\"WORK_COMMON_SCOPE\"," +
                    "\"canonical_key\":\"work.common_scope\"," +
                    "\"content\":\"用户常用工作范围是 Java 开发\",\"confidence\":0.95," +
                    "\"expires_at\":\"2027-03-11T00:00:00Z\"}}]}}"
                    : "{\"acknowledged\":true,\"deleted\":1}";
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try {
            ElasticsearchProperties shared = new ElasticsearchProperties();
            shared.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            UserMemoryIndexProperties memory = new UserMemoryIndexProperties();
            ElasticsearchMemoryKeywordIndex index =
                    new ElasticsearchMemoryKeywordIndex(shared, memory);
            MemoryIndexDocument document = new MemoryIndexDocument(
                    "m-1", 1, 74680, 3, 2,
                    "AUTO_EXTRACT", "WORK_COMMON_SCOPE", "work.common_scope",
                    "用户常用工作范围是 Java 开发", 0.95,
                    Instant.parse("2027-03-11T00:00:00Z"));

            index.ensureReady();
            index.upsert(document);
            var hits = index.search(1, 74680, 3, "使用什么编程语言", 20);
            index.delete(1, 74680, 3, "m-1");
            index.deleteExplicitScope(1, 74680, 3);
            index.clearGeneration(1, 74680, 3);

            assertThat(hits).singleElement().satisfies(hit -> {
                assertThat(hit.document().memoryId()).isEqualTo("m-1");
                assertThat(hit.score()).isEqualTo(4.2);
            });
            assertThat(requests).anySatisfy(value -> assertThat(value)
                    .startsWith("PUT /agent-user-memory-v1")
                    .contains("agent-user-memory-active")
                    .contains("ik_max_word"));
            assertThat(requests).anySatisfy(value -> assertThat(value)
                    .startsWith("PUT /agent-user-memory-active/_doc/m-1")
                    .contains("\"tenant_id\":1")
                    .contains("\"user_id\":74680")
                    .contains("\"memory_generation\":3"));
            assertThat(requests).anySatisfy(value -> assertThat(value)
                    .startsWith("POST /agent-user-memory-active/_search")
                    .contains("\"tenant_id\":1")
                    .contains("\"user_id\":74680")
                    .contains("\"memory_generation\":3")
                    .contains("expires_at")
                    .contains("使用什么编程语言"));
            assertThat(requests.stream()
                    .filter(value -> value.contains("_delete_by_query")))
                    .hasSize(3);
        } finally {
            server.stop(0);
        }
    }
}

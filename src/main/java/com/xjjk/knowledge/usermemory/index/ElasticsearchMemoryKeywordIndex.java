package com.xjjk.knowledge.usermemory.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.retrieval.index.ElasticsearchProperties;
import com.xjjk.knowledge.retrieval.index.SearchIndexUnavailableException;
import com.xjjk.knowledge.usermemory.config.UserMemoryIndexProperties;
import com.xjjk.knowledge.usermemory.domain.MemoryIndexDocument;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 与企业知识索引完全隔离的用户记忆 Elasticsearch 适配器。 */
@Component
public class ElasticsearchMemoryKeywordIndex implements MemoryKeywordIndex {
    private final ElasticsearchProperties shared;
    private final UserMemoryIndexProperties properties;
    private final HttpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;

    public ElasticsearchMemoryKeywordIndex(
            ElasticsearchProperties shared,
            UserMemoryIndexProperties properties) {
        this.shared = shared;
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(shared.getConnectTimeout())
                .build();
        this.baseUrl = stripSlash(shared.getBaseUrl());
    }

    @Override
    public void ensureReady() {
        String alias = properties.getElasticsearch().getIndexAlias();
        if (status("HEAD", "/" + alias) == 200) {
            return;
        }
        String indexName = properties.getElasticsearch().getIndexName();
        int physicalStatus = status("HEAD", "/" + indexName);
        if (physicalStatus == 404) {
            Map<String, Object> body = Map.of(
                    "settings", Map.of("index", Map.of("number_of_shards", 1)),
                    "mappings", Map.of("properties", mapping()),
                    "aliases", Map.of(alias, Map.of()));
            sendJson("PUT", "/" + indexName, body);
            return;
        }
        if (physicalStatus != 200) {
            throw new SearchIndexUnavailableException(
                    "检查用户记忆 Elasticsearch Index 失败: HTTP " + physicalStatus);
        }
        sendJson("POST", "/_aliases", Map.of("actions", List.of(
                Map.of("add", Map.of("index", indexName, "alias", alias)))));
    }

    @Override
    public void upsert(MemoryIndexDocument document) {
        sendJson("PUT", "/" + alias() + "/_doc/" + document.memoryId()
                + "?refresh=wait_for", toDocument(document));
    }

    @Override
    public void delete(long tenantId, long userId, long generation, String memoryId) {
        List<Map<String, Object>> filters = ownerFilters(tenantId, userId, generation);
        filters.add(Map.of("term", Map.of("memory_id", memoryId)));
        deleteByQuery(filters);
    }

    @Override
    public void deleteExplicitScope(long tenantId, long userId, long generation) {
        List<Map<String, Object>> filters = ownerFilters(tenantId, userId, generation);
        filters.add(Map.of("term", Map.of("source_type", "USER_EXPLICIT")));
        deleteByQuery(filters);
    }

    @Override
    public void clearGeneration(long tenantId, long userId, long generation) {
        deleteByQuery(ownerFilters(tenantId, userId, generation));
    }

    @Override
    public List<MemorySearchHit> search(
            long tenantId, long userId, long generation, String query, int topK) {
        if (tenantId <= 0 || userId <= 0 || generation <= 0
                || query == null || query.isBlank() || topK <= 0) {
            throw new IllegalArgumentException("用户记忆关键词检索参数不合法");
        }
        List<Map<String, Object>> filters = ownerFilters(tenantId, userId, generation);
        filters.add(Map.of("bool", Map.of(
                "should", List.of(
                        Map.of("bool", Map.of("must_not", List.of(
                                Map.of("exists", Map.of("field", "expires_at"))))),
                        Map.of("range", Map.of("expires_at", Map.of("gt", "now")))),
                "minimum_should_match", 1)));
        Map<String, Object> body = Map.of(
                "size", topK,
                "query", Map.of("bool", Map.of(
                        "filter", filters,
                        "must", List.of(Map.of("multi_match", Map.of(
                                "query", query.trim(),
                                "fields", List.of("content^2", "category", "canonical_key"),
                                "analyzer", "ik_smart"))))));
        JsonNode response = sendJson("POST", "/" + alias() + "/_search", body);
        List<MemorySearchHit> hits = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            hits.add(new MemorySearchHit(
                    fromDocument(hit.path("_source")),
                    hit.path("_score").asDouble(), "KEYWORD"));
        }
        return List.copyOf(hits);
    }

    private Map<String, Object> mapping() {
        return Map.ofEntries(
                Map.entry("memory_id", Map.of("type", "keyword")),
                Map.entry("tenant_id", Map.of("type", "long")),
                Map.entry("user_id", Map.of("type", "long")),
                Map.entry("memory_generation", Map.of("type", "long")),
                Map.entry("memory_version", Map.of("type", "long")),
                Map.entry("source_type", Map.of("type", "keyword")),
                Map.entry("category", Map.of("type", "keyword")),
                Map.entry("canonical_key", Map.of("type", "keyword")),
                Map.entry("content", Map.of("type", "text", "analyzer", "ik_max_word",
                        "search_analyzer", "ik_smart")),
                Map.entry("confidence", Map.of("type", "double")),
                Map.entry("expires_at", Map.of("type", "date")));
    }

    private Map<String, Object> toDocument(MemoryIndexDocument document) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("memory_id", document.memoryId());
        value.put("tenant_id", document.tenantId());
        value.put("user_id", document.userId());
        value.put("memory_generation", document.memoryGeneration());
        value.put("memory_version", document.memoryVersion());
        value.put("source_type", document.sourceType());
        value.put("category", document.category());
        value.put("canonical_key", document.canonicalKey());
        value.put("content", document.content());
        value.put("confidence", document.confidence());
        if (document.expiresAt() != null) {
            value.put("expires_at", document.expiresAt().toString());
        }
        return value;
    }

    private MemoryIndexDocument fromDocument(JsonNode source) {
        JsonNode expires = source.get("expires_at");
        return new MemoryIndexDocument(
                source.path("memory_id").asText(),
                source.path("tenant_id").asLong(),
                source.path("user_id").asLong(),
                source.path("memory_generation").asLong(),
                source.path("memory_version").asLong(),
                source.path("source_type").asText(),
                source.path("category").asText(),
                source.path("canonical_key").asText(),
                source.path("content").asText(),
                source.path("confidence").asDouble(),
                expires == null || expires.isNull() || expires.asText().isBlank()
                        ? null : Instant.parse(expires.asText()));
    }

    private List<Map<String, Object>> ownerFilters(
            long tenantId, long userId, long generation) {
        if (tenantId <= 0 || userId <= 0 || generation <= 0) {
            throw new IllegalArgumentException("用户记忆所有者范围不合法");
        }
        return new ArrayList<>(List.of(
                Map.of("term", Map.of("tenant_id", tenantId)),
                Map.of("term", Map.of("user_id", userId)),
                Map.of("term", Map.of("memory_generation", generation))));
    }

    private void deleteByQuery(List<Map<String, Object>> filters) {
        sendJson("POST", "/" + alias()
                + "/_delete_by_query?refresh=true&conflicts=proceed",
                Map.of("query", Map.of("bool", Map.of("filter", filters))));
    }

    private String alias() {
        return properties.getElasticsearch().getIndexAlias();
    }

    private int status(String method, String path) {
        return send(method, path, null, null).statusCode();
    }

    private JsonNode sendJson(String method, String path, Object body) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            HttpResponse<byte[]> response = send(
                    method, path, json, "application/json");
            if (response.statusCode() / 100 != 2) {
                throw new SearchIndexUnavailableException(
                        "用户记忆 Elasticsearch 返回 HTTP " + response.statusCode());
            }
            return response.body().length == 0
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(response.body());
        } catch (SearchIndexUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SearchIndexUnavailableException(
                    "用户记忆 Elasticsearch 请求失败", exception);
        }
    }

    private HttpResponse<byte[]> send(
            String method, String path, byte[] body, String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(shared.getReadTimeout());
            if (shared.getUsername() != null && !shared.getUsername().isBlank()) {
                String credentials = shared.getUsername() + ":"
                        + (shared.getPassword() == null ? "" : shared.getPassword());
                builder.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SearchIndexUnavailableException(
                    "调用用户记忆 Elasticsearch 失败", exception);
        }
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

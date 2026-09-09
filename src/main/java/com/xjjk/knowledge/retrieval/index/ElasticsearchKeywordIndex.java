package com.xjjk.knowledge.retrieval.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.RecallCandidate;
import com.xjjk.knowledge.retrieval.model.RecallSource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Elasticsearch 中文 BM25 适配器，草稿与发布使用不同 Index。 */
@Component
public class ElasticsearchKeywordIndex implements KeywordIndex {
    private final ElasticsearchProperties properties;
    private final HttpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;

    public ElasticsearchKeywordIndex(ElasticsearchProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout())
                .build();
        this.baseUrl = stripSlash(properties.getBaseUrl());
    }

    @Override
    public void ensureReady() {
        ensureIndex(properties.getDraftIndex());
        ensureIndex(properties.getPublishedIndex());
    }

    @Override
    public void replaceVersion(IndexLayer layer, List<IndexChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        IndexChunk first = chunks.getFirst();
        deleteVersion(layer, first.tenantId(), first.documentId(), first.versionId());
        StringBuilder ndjson = new StringBuilder();
        try {
            for (IndexChunk chunk : chunks) {
                ndjson.append(objectMapper.writeValueAsString(Map.of(
                        "index", Map.of("_index", properties.indexName(layer), "_id", chunk.chunkId())))).append('\n');
                ndjson.append(objectMapper.writeValueAsString(toDocument(chunk))).append('\n');
            }
            // 后续会立刻通过 _search 校验清单；wait_for 保证本批数据已对搜索可见，
            // 避免 Elasticsearch 近实时刷新窗口导致版本被误判为索引失败。
            JsonNode response = sendJson(
                    "POST", "/_bulk?refresh=wait_for", ndjson.toString(), "application/x-ndjson");
            if (response.path("errors").asBoolean(false)) {
                throw new SearchIndexUnavailableException("Elasticsearch Bulk 存在失败记录");
            }
        } catch (SearchIndexUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SearchIndexUnavailableException("构造 Elasticsearch Bulk 失败", exception);
        }
    }

    @Override
    public List<RecallCandidate> search(
            IndexLayer layer, long tenantId, List<Long> knowledgeBaseIds, String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            throw new IllegalArgumentException("检索问题不能为空且 topK 必须大于 0");
        }
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("tenantId", tenantId)));
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            filters.add(Map.of("terms", Map.of("knowledgeBaseId", knowledgeBaseIds)));
        }
        Map<String, Object> body = Map.of(
                "size", topK,
                "query", Map.of("bool", Map.of(
                        "filter", filters,
                        "must", List.of(Map.of("multi_match", Map.of(
                                "query", query.trim(),
                                "fields", List.of("documentTitle^2", "titlePath^1.5", "content"),
                                "analyzer", "ik_smart"))))));
        JsonNode response = sendJson("POST", "/" + properties.indexName(layer) + "/_search", body);
        List<RecallCandidate> candidates = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            candidates.add(new RecallCandidate(fromDocument(hit.path("_source")),
                    hit.path("_score").asDouble(), RecallSource.KEYWORD));
        }
        return List.copyOf(candidates);
    }

    @Override
    public IndexVerification verifyVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {
        Map<String, Object> body = Map.of(
                "size", properties.getVerificationLimit(),
                "_source", List.of("chunkId", "contentSha256"),
                "query", Map.of("bool", Map.of("filter", versionFilters(tenantId, documentId, versionId))));
        JsonNode response = sendJson("POST", "/" + properties.indexName(layer) + "/_search", body);
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            fingerprints.put(source.path("chunkId").asText(), source.path("contentSha256").asText());
        }
        return new IndexVerification(fingerprints);
    }

    @Override
    public void deleteVersion(IndexLayer layer, long tenantId, long documentId, long versionId) {
        Map<String, Object> body = Map.of(
                "query", Map.of("bool", Map.of("filter", versionFilters(tenantId, documentId, versionId))));
        sendJson("POST", "/" + properties.indexName(layer) + "/_delete_by_query?refresh=true&conflicts=proceed", body);
    }

    private void ensureIndex(String name) {
        int status = send("HEAD", "/" + name, null, null).statusCode();
        if (status == 200) {
            return;
        }
        if (status != 404) {
            throw new SearchIndexUnavailableException("检查 Elasticsearch Index 失败: HTTP " + status);
        }
        Map<String, Object> mapping = Map.of("mappings", Map.of("properties", Map.ofEntries(
                Map.entry("chunkId", Map.of("type", "keyword")),
                Map.entry("tenantId", Map.of("type", "long")),
                Map.entry("knowledgeBaseId", Map.of("type", "long")),
                Map.entry("documentId", Map.of("type", "long")),
                Map.entry("versionId", Map.of("type", "long")),
                Map.entry("chunkIndex", Map.of("type", "integer")),
                Map.entry("documentTitle", Map.of("type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart")),
                Map.entry("titlePath", Map.of("type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart")),
                Map.entry("content", Map.of("type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart")),
                Map.entry("contentSha256", Map.of("type", "keyword")),
                Map.entry("locationJson", Map.of("type", "keyword", "index", false)))));
        sendJson("PUT", "/" + name, mapping);
    }

    private List<Map<String, Object>> versionFilters(long tenantId, long documentId, long versionId) {
        return List.of(
                Map.of("term", Map.of("tenantId", tenantId)),
                Map.of("term", Map.of("documentId", documentId)),
                Map.of("term", Map.of("versionId", versionId)));
    }

    private Map<String, Object> toDocument(IndexChunk chunk) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("chunkId", chunk.chunkId());
        document.put("tenantId", chunk.tenantId());
        document.put("knowledgeBaseId", chunk.knowledgeBaseId());
        document.put("documentId", chunk.documentId());
        document.put("versionId", chunk.versionId());
        document.put("chunkIndex", chunk.chunkIndex());
        document.put("documentTitle", chunk.documentTitle());
        document.put("titlePath", chunk.titlePath());
        document.put("content", chunk.content());
        document.put("contentSha256", chunk.contentSha256());
        document.put("locationJson", chunk.locationJson());
        return document;
    }

    private IndexChunk fromDocument(JsonNode source) {
        return new IndexChunk(
                source.path("chunkId").asText(), source.path("tenantId").asLong(),
                source.path("knowledgeBaseId").asLong(), source.path("documentId").asLong(),
                source.path("versionId").asLong(), source.path("chunkIndex").asInt(),
                textOrNull(source, "documentTitle"), textOrNull(source, "titlePath"),
                source.path("content").asText(), source.path("contentSha256").asText(),
                textOrNull(source, "locationJson"));
    }

    private String textOrNull(JsonNode source, String field) {
        JsonNode value = source.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private JsonNode sendJson(String method, String path, Object body) {
        try {
            return sendJson(method, path, objectMapper.writeValueAsString(body), "application/json");
        } catch (SearchIndexUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SearchIndexUnavailableException("序列化 Elasticsearch 请求失败", exception);
        }
    }

    private JsonNode sendJson(String method, String path, String body, String contentType) {
        HttpResponse<byte[]> response = send(method, path, body, contentType);
        if (response.statusCode() / 100 != 2) {
            throw new SearchIndexUnavailableException("Elasticsearch 返回 HTTP " + response.statusCode());
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception exception) {
            throw new SearchIndexUnavailableException("Elasticsearch 返回非法 JSON", exception);
        }
    }

    private HttpResponse<byte[]> send(String method, String path, String body, String contentType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(properties.getReadTimeout());
            if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
                String credentials = properties.getUsername() + ":" + (properties.getPassword() == null ? "" : properties.getPassword());
                builder.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SearchIndexUnavailableException("调用 Elasticsearch 失败", exception);
        }
    }

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

package com.xjjk.knowledge.retrieval.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** 调用 Ollama `/api/embed` 的 Qwen3 Embedding 适配器。 */
@Component
public class OllamaEmbeddingClient implements EmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);

    private final EmbeddingProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;

    public OllamaEmbeddingClient(EmbeddingProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout())
                .build();
        this.objectMapper = new ObjectMapper();
        this.endpoint = URI.create(stripTrailingSlash(properties.getBaseUrl()) + "/api/embed");
    }

    @Override
    public List<List<Float>> embedDocuments(List<String> documents) {
        if (documents == null) {
            throw new IllegalArgumentException("文档列表不能为空");
        }
        List<List<Float>> vectors = new ArrayList<>();
        for (int from = 0; from < documents.size(); from += properties.getBatchSize()) {
            int until = Math.min(from + properties.getBatchSize(), documents.size());
            List<String> inputs = documents.subList(from, until).stream()
                    .map(this::requireText)
                    .map(text -> EmbeddingProperties.DOCUMENT_INSTRUCTION + text)
                    .toList();
            vectors.addAll(embed(inputs));
        }
        return List.copyOf(vectors);
    }

    @Override
    public List<Float> embedQuery(String query) {
        return embed(List.of(EmbeddingProperties.QUERY_INSTRUCTION + requireText(query))).getFirst();
    }

    private List<List<Float>> embed(List<String> inputs) {
        long startedAt = System.nanoTime();
        try {
            byte[] requestBody = objectMapper.writeValueAsBytes(new EmbedRequest(
                    properties.getModel(), inputs, false, properties.getDimension()));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(properties.getReadTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new EmbeddingUnavailableException("Embedding 服务返回 HTTP " + response.statusCode());
            }
            EmbedResponse body = objectMapper.readValue(response.body(), EmbedResponse.class);
            validate(body, inputs.size());
            log.info("knowledge_embedding_completed batchSize={}, durationMs={}",
                    inputs.size(), (System.nanoTime() - startedAt) / 1_000_000);
            return body.embeddings().stream().map(List::copyOf).toList();
        } catch (EmbeddingUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new EmbeddingUnavailableException("调用 Embedding 服务失败", exception);
        }
    }

    private void validate(EmbedResponse response, int expectedCount) {
        if (response == null || response.embeddings() == null
                || response.embeddings().size() != expectedCount) {
            throw new EmbeddingUnavailableException("Embedding 响应数量不一致");
        }
        for (List<Float> vector : response.embeddings()) {
            if (vector == null || vector.size() != properties.getDimension()) {
                throw new EmbeddingUnavailableException("Embedding 响应维度不一致");
            }
            if (vector.stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
                throw new EmbeddingUnavailableException("Embedding 响应包含非法数值");
            }
        }
    }

    private String requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding 文本不能为空");
        }
        return text.trim();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record EmbedRequest(String model, List<String> input, boolean truncate, int dimensions) {}
    /** Ollama 会附带耗时、Token 数等运行时元数据，客户端只读取稳定的向量字段。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbedResponse(String model, List<List<Float>> embeddings) {}
}

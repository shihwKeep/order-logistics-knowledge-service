package com.xjjk.knowledge.retrieval.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.cloud.budget.CloudModelCallType;
import com.xjjk.knowledge.cloud.budget.CloudModelCostEstimator;
import com.xjjk.knowledge.cloud.client.BailianCallExecutor;
import com.xjjk.knowledge.cloud.client.BailianCallResult;
import com.xjjk.knowledge.cloud.client.BailianProviderException;
import com.xjjk.knowledge.cloud.config.BailianModelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class BailianEmbeddingClient implements EmbeddingClient {
    static final String QUERY_INSTRUCTION =
            "Given a Chinese customer-service question, retrieve passages that directly answer it.";
    private static final Logger log = LoggerFactory.getLogger(BailianEmbeddingClient.class);

    private final BailianModelProperties properties;
    private final BailianCallExecutor executor;
    private final CloudModelCostEstimator costEstimator;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BailianEmbeddingClient(BailianModelProperties properties,
                                  BailianCallExecutor executor,
                                  CloudModelCostEstimator costEstimator) {
        this.properties = properties;
        this.executor = executor;
        this.costEstimator = costEstimator;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout()).build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<List<Float>> embedDocuments(List<String> documents) {
        if (documents == null) throw new IllegalArgumentException("文档列表不能为空");
        List<String> normalized = documents.stream().map(this::requireText).toList();
        List<List<Float>> vectors = new ArrayList<>();
        for (int from = 0; from < normalized.size(); from += properties.getEmbeddingBatchSize()) {
            int until = Math.min(from + properties.getEmbeddingBatchSize(), normalized.size());
            vectors.addAll(embed(normalized.subList(from, until), "document", null));
        }
        return List.copyOf(vectors);
    }

    @Override
    public List<Float> embedQuery(String query) {
        return embed(List.of(requireText(query)), "query", QUERY_INSTRUCTION).getFirst();
    }

    private List<List<Float>> embed(List<String> texts, String textType, String instruction) {
        long maximumCharge = costEstimator.embeddingMaximumCharge(texts, instruction,
                properties.getEmbeddingPriceMicrosPerMillionTokens());
        String logicalRequestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        List<List<Float>> result = executor.execute(logicalRequestId, CloudModelCallType.EMBEDDING,
                properties.getEmbeddingModel(), maximumCharge,
                () -> send(texts, textType, instruction));
        log.info("knowledge_embedding_completed batchSize={}, textType={}, durationMs={}",
                texts.size(), textType, (System.nanoTime() - startedAt) / 1_000_000);
        return result;
    }

    private BailianCallResult<List<List<Float>>> send(
            List<String> texts, String textType, String instruction) throws Exception {
        byte[] requestBody = objectMapper.writeValueAsBytes(new EmbedRequest(
                properties.getEmbeddingModel(), new EmbedInput(texts),
                new EmbedParameters(textType, properties.getEmbeddingDimension(), "dense", instruction)));
        HttpRequest request = HttpRequest.newBuilder(properties.embeddingEndpoint())
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(properties.getReadTimeout())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) throw providerFailure(response);
        EmbedResponse body = objectMapper.readValue(response.body(), EmbedResponse.class);
        return new BailianCallResult<>(mapAndValidate(body, texts.size()),
                requireUsage(body), body.requestId());
    }

    private BailianProviderException providerFailure(HttpResponse<byte[]> response) {
        String code = "HTTP_" + response.statusCode();
        try {
            ErrorResponse body = objectMapper.readValue(response.body(), ErrorResponse.class);
            if (body.code() != null && !body.code().isBlank()) code = body.code();
        } catch (Exception ignored) {
            // Only the stable code is retained; provider bodies are never logged.
        }
        boolean retryable = response.statusCode() == 429 || response.statusCode() == 502
                || response.statusCode() == 503 || response.statusCode() == 504;
        return new BailianProviderException(code, response.statusCode(), retryable, true);
    }

    private List<List<Float>> mapAndValidate(EmbedResponse response, int expectedCount) {
        if (response == null || response.output() == null || response.output().embeddings() == null
                || response.output().embeddings().size() != expectedCount) {
            throw new EmbeddingUnavailableException("Embedding 响应数量不一致");
        }
        Set<Integer> seen = new HashSet<>();
        List<EmbeddingItem> ordered = response.output().embeddings().stream()
                .sorted(Comparator.comparingInt(EmbeddingItem::index)).toList();
        for (EmbeddingItem item : ordered) {
            if (item == null || item.index() < 0 || item.index() >= expectedCount
                    || !seen.add(item.index()) || item.embedding() == null
                    || item.embedding().size() != properties.getEmbeddingDimension()
                    || item.embedding().stream().anyMatch(value -> value == null || !Float.isFinite(value))) {
                throw new EmbeddingUnavailableException("Embedding 响应索引、维度或数值不合法");
            }
        }
        return ordered.stream().map(item -> List.copyOf(item.embedding())).toList();
    }

    private long requireUsage(EmbedResponse response) {
        if (response.usage() == null || response.usage().totalTokens() <= 0) {
            throw new EmbeddingUnavailableException("Embedding 响应缺少 Token 用量");
        }
        return response.usage().totalTokens();
    }

    private String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Embedding 文本不能为空");
        return value.trim();
    }

    private record EmbedRequest(String model, EmbedInput input, EmbedParameters parameters) {}
    private record EmbedInput(List<String> texts) {}
    private record EmbedParameters(@JsonProperty("text_type") String textType,
                                   int dimension,
                                   @JsonProperty("output_type") String outputType,
                                   String instruct) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbedResponse(Output output, Usage usage,
                                 @JsonProperty("request_id") String requestId) {}
    private record Output(List<EmbeddingItem> embeddings) {}
    private record EmbeddingItem(List<Float> embedding, int index) {}
    private record Usage(@JsonProperty("total_tokens") long totalTokens) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorResponse(String code) {}
}

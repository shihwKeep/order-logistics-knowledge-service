package com.xjjk.knowledge.retrieval.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.cloud.budget.CloudModelCallType;
import com.xjjk.knowledge.cloud.budget.CloudModelCostEstimator;
import com.xjjk.knowledge.cloud.client.BailianCallExecutor;
import com.xjjk.knowledge.cloud.client.BailianCallResult;
import com.xjjk.knowledge.cloud.client.BailianProviderException;
import com.xjjk.knowledge.cloud.config.BailianModelProperties;
import com.xjjk.knowledge.retrieval.model.RankedEvidence;
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
public class BailianRerankerClient implements Reranker {
    static final String RERANK_INSTRUCTION =
            "Given a Chinese customer-service question, retrieve passages that directly answer it.";
    private static final Logger log = LoggerFactory.getLogger(BailianRerankerClient.class);

    private final BailianModelProperties cloud;
    private final RerankerProperties reranker;
    private final BailianCallExecutor executor;
    private final CloudModelCostEstimator costEstimator;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BailianRerankerClient(BailianModelProperties cloud, RerankerProperties reranker,
                                 BailianCallExecutor executor,
                                 CloudModelCostEstimator costEstimator) {
        this.cloud = cloud;
        this.reranker = reranker;
        this.executor = executor;
        this.costEstimator = costEstimator;
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(cloud.getConnectTimeout()).build();
    }

    @Override
    public List<RankedEvidence> rerank(String query, List<RankedEvidence> candidates) {
        if (query == null || query.isBlank() || candidates == null) {
            throw new IllegalArgumentException("精排问题和候选不能为空");
        }
        if (candidates.isEmpty()) return List.of();
        if (!reranker.isEnabled()) throw new RerankerUnavailableException("Reranker 未启用");
        if (candidates.stream().anyMatch(item -> item == null || item.chunk() == null
                || item.chunk().content() == null || item.chunk().content().isBlank())) {
            throw new IllegalArgumentException("精排候选正文不能为空");
        }
        String normalizedQuery = query.trim();
        List<String> documents = candidates.stream().map(item -> item.chunk().content()).toList();
        long maximumCharge = costEstimator.rerankMaximumCharge(normalizedQuery, documents,
                RERANK_INSTRUCTION, cloud.getRerankerPriceMicrosPerMillionTokens());
        int topK = Math.min(reranker.getTopK(), candidates.size());
        long startedAt = System.nanoTime();
        List<RankedEvidence> result = executor.execute(UUID.randomUUID().toString(),
                CloudModelCallType.RERANK, cloud.getRerankerModel(), maximumCharge,
                () -> send(normalizedQuery, documents, candidates, topK));
        log.info("knowledge_rerank_completed inputCount={}, outputCount={}, durationMs={}",
                candidates.size(), result.size(), (System.nanoTime() - startedAt) / 1_000_000);
        return result;
    }

    private BailianCallResult<List<RankedEvidence>> send(
            String query, List<String> documents, List<RankedEvidence> candidates, int topK)
            throws Exception {
        byte[] requestBody = objectMapper.writeValueAsBytes(new RerankRequest(
                cloud.getRerankerModel(), new RerankInput(query, documents),
                new RerankParameters(topK, RERANK_INSTRUCTION)));
        HttpRequest request = HttpRequest.newBuilder(cloud.rerankEndpoint())
                .version(HttpClient.Version.HTTP_1_1).timeout(cloud.getReadTimeout())
                .header("Authorization", "Bearer " + cloud.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) throw providerFailure(response);
        RerankResponse body = objectMapper.readValue(response.body(), RerankResponse.class);
        if (body == null || body.usage() == null || body.usage().totalTokens() <= 0) {
            throw new RerankerUnavailableException("Reranker 响应缺少 Token 用量");
        }
        return new BailianCallResult<>(mapAndValidate(body, candidates, topK),
                body.usage().totalTokens(), body.requestId());
    }

    private BailianProviderException providerFailure(HttpResponse<byte[]> response) {
        String code = "HTTP_" + response.statusCode();
        try {
            ErrorResponse body = objectMapper.readValue(response.body(), ErrorResponse.class);
            if (body.code() != null && !body.code().isBlank()) code = body.code();
        } catch (Exception ignored) {
            // Provider response text is deliberately not retained or logged.
        }
        boolean retryable = response.statusCode() == 429 || response.statusCode() == 502
                || response.statusCode() == 503 || response.statusCode() == 504;
        return new BailianProviderException(code, response.statusCode(), retryable, true);
    }

    private List<RankedEvidence> mapAndValidate(
            RerankResponse response, List<RankedEvidence> candidates, int topK) {
        if (response.output() == null || response.output().results() == null
                || response.output().results().isEmpty()) {
            throw new RerankerUnavailableException("Reranker 响应结果不能为空");
        }
        Set<Integer> seen = new HashSet<>();
        List<RankedEvidence> mapped = new ArrayList<>();
        for (RerankResult item : response.output().results()) {
            if (item == null || item.index() < 0 || item.index() >= candidates.size()
                    || !seen.add(item.index()) || !Double.isFinite(item.relevanceScore())
                    || item.relevanceScore() < 0D || item.relevanceScore() > 1D) {
                throw new RerankerUnavailableException("Reranker 返回越界、重复或非法分数");
            }
            mapped.add(candidates.get(item.index()).withScore(item.relevanceScore()));
        }
        return mapped.stream().sorted(Comparator.comparingDouble(RankedEvidence::score).reversed()
                        .thenComparing(item -> item.chunk().chunkId()))
                .limit(topK).toList();
    }

    private record RerankRequest(String model, RerankInput input, RerankParameters parameters) {}
    private record RerankInput(String query, List<String> documents) {}
    private record RerankParameters(@JsonProperty("top_n") int topN, String instruct) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankResponse(Output output, Usage usage,
                                  @JsonProperty("request_id") String requestId) {}
    private record Output(List<RerankResult> results) {}
    private record RerankResult(int index,
                                @JsonProperty("relevance_score") double relevanceScore) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(@JsonProperty("total_tokens") long totalTokens) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorResponse(String code) {}
}

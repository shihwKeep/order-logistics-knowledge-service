package com.xjjk.knowledge.retrieval.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 本地 BGE Cross-Encoder 的 `/rerank` HTTP 契约适配器。 */
@Component
public class BgeRerankerClient implements Reranker {
    private static final Logger log = LoggerFactory.getLogger(BgeRerankerClient.class);

    private final RerankerProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI endpoint;

    public BgeRerankerClient(RerankerProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout())
                .build();
        String baseUrl = properties.getBaseUrl();
        this.endpoint = URI.create((baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + "/rerank");
    }

    @Override
    public List<RankedEvidence> rerank(String query, List<RankedEvidence> candidates) {
        if (query == null || query.isBlank() || candidates == null) {
            throw new IllegalArgumentException("精排问题和候选不能为空");
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (!properties.isEnabled()) {
            throw new RerankerUnavailableException("Reranker 未启用");
        }
        if (candidates.stream().anyMatch(item -> item == null || item.chunk() == null
                || item.chunk().content() == null || item.chunk().content().isBlank())) {
            throw new IllegalArgumentException("精排候选正文不能为空");
        }
        long startedAt = System.nanoTime();
        try {
            int topK = Math.min(properties.getTopK(), candidates.size());
            byte[] requestBody = objectMapper.writeValueAsBytes(new RerankRequest(
                    query.trim(), candidates.stream().map(item -> item.chunk().content()).toList(), topK));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(properties.getReadTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new RerankerUnavailableException("Reranker 返回 HTTP " + response.statusCode());
            }
            RerankResponse body = objectMapper.readValue(response.body(), RerankResponse.class);
            List<RankedEvidence> result = mapAndValidate(body, candidates, topK);
            // 日志只包含数量与耗时，禁止记录用户问题和知识正文。
            log.info("knowledge_rerank_completed inputCount={}, outputCount={}, durationMs={}",
                    candidates.size(), result.size(), (System.nanoTime() - startedAt) / 1_000_000);
            return result;
        } catch (RerankerUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RerankerUnavailableException("Reranker 响应不合法或调用失败", exception);
        }
    }

    private List<RankedEvidence> mapAndValidate(
            RerankResponse response, List<RankedEvidence> candidates, int topK) {
        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new IllegalStateException("Reranker 响应结果不能为空");
        }
        Set<Integer> seen = new HashSet<>();
        List<RankedEvidence> mapped = new ArrayList<>();
        for (RerankResult result : response.results()) {
            if (result == null || result.index() < 0 || result.index() >= candidates.size()
                    || !seen.add(result.index()) || !Double.isFinite(result.relevanceScore())
                    || result.relevanceScore() < 0D || result.relevanceScore() > 1D) {
                throw new IllegalStateException("Reranker 返回越界、重复或非法分数");
            }
            mapped.add(candidates.get(result.index()).withScore(result.relevanceScore()));
        }
        return mapped.stream()
                .sorted(Comparator.comparingDouble(RankedEvidence::score).reversed()
                        .thenComparing(item -> item.chunk().chunkId()))
                .limit(topK)
                .toList();
    }

    private record RerankRequest(String query, List<String> documents, int topK) {
    }

    private record RerankResponse(List<RerankResult> results) {
    }

    private record RerankResult(int index, double relevanceScore) {
    }
}

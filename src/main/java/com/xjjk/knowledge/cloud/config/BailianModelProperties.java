package com.xjjk.knowledge.cloud.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "knowledge.cloud-model")
public class BailianModelProperties {
    private boolean enabled = true;
    private String region = "cn-beijing";
    private String workspaceId;
    private String apiKey;
    private String baseUrl;
    private String embeddingModel = "qwen3.7-text-embedding";
    private String rerankerModel = "qwen3.7-text-rerank";
    private int embeddingDimension = 2560;
    private int embeddingBatchSize = 20;
    private int maxAttempts = 3;
    private Duration initialBackoff = Duration.ofMillis(200);
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(30);
    private long monthlyBudgetMicros = 200_000_000L;
    private long hardLimitMicros = 180_000_000L;
    private long embeddingPriceMicrosPerMillionTokens = 500_000L;
    private long rerankerPriceMicrosPerMillionTokens = 500_000L;

    public URI embeddingEndpoint() {
        return endpoint("embeddings/text-embedding/text-embedding");
    }

    public URI rerankEndpoint() {
        return endpoint("rerank/text-rerank/text-rerank");
    }

    private URI endpoint(String suffix) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            String normalized = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return URI.create(normalized + "/api/v1/services/" + suffix);
        }
        return URI.create("https://" + workspaceId + "." + region
                + ".maas.aliyuncs.com/api/v1/services/" + suffix);
    }

    @PostConstruct
    public void validate() {
        if (!enabled) return;
        if (workspaceId == null || workspaceId.isBlank()
                || apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("百炼 Workspace 和 API Key 不能为空");
        }
        if (!"cn-beijing".equals(region)
                || embeddingModel == null || embeddingModel.isBlank()
                || rerankerModel == null || rerankerModel.isBlank()
                || embeddingDimension != 2560
                || embeddingBatchSize < 1 || embeddingBatchSize > 20
                || maxAttempts < 1 || maxAttempts > 5
                || initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                || monthlyBudgetMicros <= 0 || hardLimitMicros <= 0
                || hardLimitMicros > monthlyBudgetMicros
                || embeddingPriceMicrosPerMillionTokens <= 0
                || rerankerPriceMicrosPerMillionTokens <= 0) {
            throw new IllegalArgumentException("百炼模型或预算配置不合法");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public String getRerankerModel() { return rerankerModel; }
    public void setRerankerModel(String rerankerModel) { this.rerankerModel = rerankerModel; }
    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    public int getEmbeddingBatchSize() { return embeddingBatchSize; }
    public void setEmbeddingBatchSize(int embeddingBatchSize) { this.embeddingBatchSize = embeddingBatchSize; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getInitialBackoff() { return initialBackoff; }
    public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public long getMonthlyBudgetMicros() { return monthlyBudgetMicros; }
    public void setMonthlyBudgetMicros(long monthlyBudgetMicros) { this.monthlyBudgetMicros = monthlyBudgetMicros; }
    public long getHardLimitMicros() { return hardLimitMicros; }
    public void setHardLimitMicros(long hardLimitMicros) { this.hardLimitMicros = hardLimitMicros; }
    public long getEmbeddingPriceMicrosPerMillionTokens() { return embeddingPriceMicrosPerMillionTokens; }
    public void setEmbeddingPriceMicrosPerMillionTokens(long value) { this.embeddingPriceMicrosPerMillionTokens = value; }
    public long getRerankerPriceMicrosPerMillionTokens() { return rerankerPriceMicrosPerMillionTokens; }
    public void setRerankerPriceMicrosPerMillionTokens(long value) { this.rerankerPriceMicrosPerMillionTokens = value; }
}

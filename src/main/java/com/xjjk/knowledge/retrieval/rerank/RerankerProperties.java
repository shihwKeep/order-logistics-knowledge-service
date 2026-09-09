package com.xjjk.knowledge.retrieval.rerank;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "knowledge.retrieval.reranker")
public class RerankerProperties {
    private boolean enabled = true;
    private String baseUrl = "http://127.0.0.1:8000";
    private int topK = 5;
    private double scoreThreshold = 0.15D;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(8);

    @PostConstruct
    void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Reranker 地址不能为空");
        }
        if (topK <= 0 || !Double.isFinite(scoreThreshold)
                || scoreThreshold < 0D || scoreThreshold > 1D) {
            throw new IllegalArgumentException("Reranker 数量或阈值不合法");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("Reranker 超时必须为正数");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public double getScoreThreshold() { return scoreThreshold; }
    public void setScoreThreshold(double scoreThreshold) { this.scoreThreshold = scoreThreshold; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}

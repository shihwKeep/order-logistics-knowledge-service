package com.xjjk.knowledge.retrieval.rerank;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "knowledge.retrieval.reranker")
public class RerankerProperties {
    private boolean enabled = true;
    private int topK = 5;
    private double scoreThreshold = 0.15D;

    @PostConstruct
    void validate() {
        if (topK <= 0 || !Double.isFinite(scoreThreshold)
                || scoreThreshold < 0D || scoreThreshold > 1D) {
            throw new IllegalArgumentException("Reranker 数量或阈值不合法");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public double getScoreThreshold() { return scoreThreshold; }
    public void setScoreThreshold(double scoreThreshold) { this.scoreThreshold = scoreThreshold; }
}

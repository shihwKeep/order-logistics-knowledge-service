package com.xjjk.knowledge.retrieval.service;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "knowledge.retrieval.strategy")
public class RetrievalProperties {
    private int recallTopK = 30;
    private int fusionTopK = 10;
    private int finalTopK = 5;
    private double vectorWeight = 1D;
    private double keywordWeight = 1D;
    private String version = "qwen3-es-milvus-rrf60-bge-v2";

    @PostConstruct
    void validate() {
        if (recallTopK <= 0 || fusionTopK <= 0 || finalTopK <= 0
                || vectorWeight <= 0D || keywordWeight <= 0D
                || version == null || version.isBlank()) {
            throw new IllegalArgumentException("知识检索策略配置不合法");
        }
    }

    public int getRecallTopK() { return recallTopK; }
    public void setRecallTopK(int recallTopK) { this.recallTopK = recallTopK; }
    public int getFusionTopK() { return fusionTopK; }
    public void setFusionTopK(int fusionTopK) { this.fusionTopK = fusionTopK; }
    public int getFinalTopK() { return finalTopK; }
    public void setFinalTopK(int finalTopK) { this.finalTopK = finalTopK; }
    public double getVectorWeight() { return vectorWeight; }
    public void setVectorWeight(double vectorWeight) { this.vectorWeight = vectorWeight; }
    public double getKeywordWeight() { return keywordWeight; }
    public void setKeywordWeight(double keywordWeight) { this.keywordWeight = keywordWeight; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
}

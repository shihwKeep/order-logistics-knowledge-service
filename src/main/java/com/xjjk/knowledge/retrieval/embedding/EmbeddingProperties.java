package com.xjjk.knowledge.retrieval.embedding;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Qwen3 Embedding 模型及批处理约束。 */
@Component
@ConfigurationProperties(prefix = "knowledge.retrieval.embedding")
public class EmbeddingProperties {
    public static final String DOCUMENT_INSTRUCTION =
            "Represent this document for retrieval in a Chinese customer-service knowledge base: ";
    public static final String QUERY_INSTRUCTION =
            "Represent this query for retrieving relevant documents from a Chinese customer-service knowledge base: ";

    private String baseUrl = "http://127.0.0.1:11434";
    private String model = "qwen3-embedding:4b-q4_K_M";
    private int dimension = 2560;
    private int batchSize = 8;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(45);
    private String instructionVersion = "qwen3-customer-service-v1";

    @PostConstruct
    void validate() {
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding 地址和模型不能为空");
        }
        if (dimension <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("Embedding 维度和批量必须大于 0");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Embedding 超时必须是正数");
        }
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public String getInstructionVersion() { return instructionVersion; }
    public void setInstructionVersion(String instructionVersion) { this.instructionVersion = instructionVersion; }
}

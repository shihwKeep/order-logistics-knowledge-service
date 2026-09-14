package com.xjjk.knowledge.retrieval.embedding;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Embedding 索引元数据与批处理约束。 */
@Component
@ConfigurationProperties(prefix = "knowledge.retrieval.embedding")
public class EmbeddingProperties {
    private String model = "qwen3.7-text-embedding";
    private int dimension = 2560;
    private int batchSize = 20;
    private String instructionVersion = "qwen37-customer-service-v2";

    @PostConstruct
    void validate() {
        if (model == null || model.isBlank() || dimension <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("Embedding 维度和批量必须大于 0");
        }
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public String getInstructionVersion() { return instructionVersion; }
    public void setInstructionVersion(String instructionVersion) { this.instructionVersion = instructionVersion; }
}

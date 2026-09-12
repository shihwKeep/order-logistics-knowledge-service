package com.xjjk.knowledge.usermemory.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 独立用户记忆索引配置；默认关闭，必须由部署配置显式启用。 */
@Component
@ConfigurationProperties(prefix = "knowledge.user-memory")
public class UserMemoryIndexProperties {
    private boolean enabled;
    private final Elasticsearch elasticsearch = new Elasticsearch();
    private final Milvus milvus = new Milvus();
    private final Retrieval retrieval = new Retrieval();

    @PostConstruct
    void validate() {
        if (!"agent-user-memory-active".equals(elasticsearch.indexAlias)
                || elasticsearch.indexName == null
                || !elasticsearch.indexName.startsWith("agent-user-memory-")
                || "agent-user-memory-active".equals(elasticsearch.indexName)) {
            throw new IllegalArgumentException("用户记忆 Elasticsearch 命名空间不合法");
        }
        if (milvus.collection == null
                || !milvus.collection.startsWith("agent_user_memory_")
                || milvus.dimension <= 0) {
            throw new IllegalArgumentException("用户记忆 Milvus 配置不合法");
        }
        if (retrieval.esTopK <= 0 || retrieval.milvusTopK <= 0
                || retrieval.rrfTopK <= 0 || retrieval.finalTopK <= 0
                || retrieval.finalTopK > retrieval.rrfTopK
                || retrieval.vectorWeight <= 0D || retrieval.keywordWeight <= 0D
                || retrieval.minVectorScore < 0D || retrieval.minVectorScore > 1D
                || retrieval.rerankerScoreThreshold < 0D
                || retrieval.rerankerScoreThreshold > 1D
                || retrieval.timeout == null || retrieval.timeout.isZero()
                || retrieval.timeout.isNegative()
                || retrieval.strategyVersion == null
                || retrieval.strategyVersion.isBlank()) {
            throw new IllegalArgumentException("用户记忆检索配置不合法");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Elasticsearch getElasticsearch() { return elasticsearch; }
    public Milvus getMilvus() { return milvus; }
    public Retrieval getRetrieval() { return retrieval; }

    public static class Elasticsearch {
        private String indexAlias = "agent-user-memory-active";
        private String indexName = "agent-user-memory-v1";

        public String getIndexAlias() { return indexAlias; }
        public void setIndexAlias(String indexAlias) { this.indexAlias = indexAlias; }
        public String getIndexName() { return indexName; }
        public void setIndexName(String indexName) { this.indexName = indexName; }
    }

    public static class Milvus {
        private String collection = "agent_user_memory_v1";
        private int dimension = 2560;

        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
    }

    public static class Retrieval {
        private int esTopK = 20;
        private int milvusTopK = 20;
        private int rrfTopK = 10;
        private int finalTopK = 10;
        private double vectorWeight = 1D;
        private double keywordWeight = 1D;
        private double minVectorScore = 0.45D;
        private boolean rerankEnabled = true;
        private double rerankerScoreThreshold = 0.50D;
        private Duration timeout = Duration.ofSeconds(3);
        private String strategyVersion = "user-memory-es-milvus-rrf60-bge-v1";

        public int getEsTopK() { return esTopK; }
        public void setEsTopK(int esTopK) { this.esTopK = esTopK; }
        public int getMilvusTopK() { return milvusTopK; }
        public void setMilvusTopK(int milvusTopK) { this.milvusTopK = milvusTopK; }
        public int getRrfTopK() { return rrfTopK; }
        public void setRrfTopK(int rrfTopK) { this.rrfTopK = rrfTopK; }
        public int getFinalTopK() { return finalTopK; }
        public void setFinalTopK(int finalTopK) { this.finalTopK = finalTopK; }
        public double getVectorWeight() { return vectorWeight; }
        public void setVectorWeight(double vectorWeight) { this.vectorWeight = vectorWeight; }
        public double getKeywordWeight() { return keywordWeight; }
        public void setKeywordWeight(double keywordWeight) { this.keywordWeight = keywordWeight; }
        public double getMinVectorScore() { return minVectorScore; }
        public void setMinVectorScore(double minVectorScore) { this.minVectorScore = minVectorScore; }
        public boolean isRerankEnabled() { return rerankEnabled; }
        public void setRerankEnabled(boolean rerankEnabled) { this.rerankEnabled = rerankEnabled; }
        public double getRerankerScoreThreshold() { return rerankerScoreThreshold; }
        public void setRerankerScoreThreshold(double value) { this.rerankerScoreThreshold = value; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public String getStrategyVersion() { return strategyVersion; }
        public void setStrategyVersion(String strategyVersion) { this.strategyVersion = strategyVersion; }
    }
}

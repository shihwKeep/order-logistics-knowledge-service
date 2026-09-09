package com.xjjk.knowledge.retrieval.index;

import com.xjjk.knowledge.retrieval.model.IndexLayer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "knowledge.retrieval.milvus")
public class MilvusProperties {
    private boolean enabled;
    private String uri = "http://127.0.0.1:19530";
    private String databaseName = "order_logistics_knowledge";
    private String token;
    private String draftCollection = "knowledge_chunks_draft_v1";
    private String publishedCollection = "knowledge_chunks_published_v1";
    private int dimension = 2560;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private int verificationLimit = 10000;

    public String collectionName(IndexLayer layer) {
        return layer == IndexLayer.DRAFT ? draftCollection : publishedCollection;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getDraftCollection() { return draftCollection; }
    public void setDraftCollection(String draftCollection) { this.draftCollection = draftCollection; }
    public String getPublishedCollection() { return publishedCollection; }
    public void setPublishedCollection(String publishedCollection) { this.publishedCollection = publishedCollection; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getVerificationLimit() { return verificationLimit; }
    public void setVerificationLimit(int verificationLimit) { this.verificationLimit = verificationLimit; }
}

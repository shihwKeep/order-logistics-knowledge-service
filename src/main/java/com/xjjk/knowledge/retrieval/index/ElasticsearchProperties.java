package com.xjjk.knowledge.retrieval.index;

import com.xjjk.knowledge.retrieval.model.IndexLayer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "knowledge.retrieval.elasticsearch")
public class ElasticsearchProperties {
    private String baseUrl = "http://127.0.0.1:9200";
    private String username;
    private String password;
    private String draftIndex = "knowledge_chunks_draft_v1";
    private String publishedIndex = "knowledge_chunks_published_v1";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);
    private int verificationLimit = 10000;

    public String indexName(IndexLayer layer) {
        return layer == IndexLayer.DRAFT ? draftIndex : publishedIndex;
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDraftIndex() { return draftIndex; }
    public void setDraftIndex(String draftIndex) { this.draftIndex = draftIndex; }
    public String getPublishedIndex() { return publishedIndex; }
    public void setPublishedIndex(String publishedIndex) { this.publishedIndex = publishedIndex; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getVerificationLimit() { return verificationLimit; }
    public void setVerificationLimit(int verificationLimit) { this.verificationLimit = verificationLimit; }
}

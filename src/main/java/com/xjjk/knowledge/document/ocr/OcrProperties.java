package com.xjjk.knowledge.document.ocr;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledge.document.ocr")
public class OcrProperties {
    private String baseUrl = "http://127.0.0.1:8091";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(15);
    private int maxResponseBytes = 2 * 1024 * 1024;
    private double lowConfidenceThreshold = 0.80;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
    public double getLowConfidenceThreshold() { return lowConfidenceThreshold; }
    public void setLowConfidenceThreshold(double lowConfidenceThreshold) { this.lowConfidenceThreshold = lowConfidenceThreshold; }
}

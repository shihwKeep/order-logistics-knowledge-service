package com.xjjk.knowledge.document.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("knowledge.document.storage")
public class DocumentStorageProperties {
    private boolean enabled;
    private String endpoint = "http://127.0.0.1:9000";
    private String accessKey;
    private String secretKey;
    private String bucket = "knowledge-documents";
    private DataSize maxFileSize = DataSize.ofMegabytes(30);
    private boolean orphanScanEnabled;
    private Duration orphanSafetyWindow = Duration.ofHours(24);
    private int orphanScanBatchSize = 100;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public DataSize getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(DataSize maxFileSize) { this.maxFileSize = maxFileSize; }
    public boolean isOrphanScanEnabled() { return orphanScanEnabled; }
    public void setOrphanScanEnabled(boolean orphanScanEnabled) { this.orphanScanEnabled = orphanScanEnabled; }
    public Duration getOrphanSafetyWindow() { return orphanSafetyWindow; }
    public void setOrphanSafetyWindow(Duration orphanSafetyWindow) { this.orphanSafetyWindow = orphanSafetyWindow; }
    public int getOrphanScanBatchSize() { return orphanScanBatchSize; }
    public void setOrphanScanBatchSize(int orphanScanBatchSize) { this.orphanScanBatchSize = orphanScanBatchSize; }
}

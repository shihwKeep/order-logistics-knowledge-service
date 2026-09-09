package com.xjjk.knowledge.document.task;

import java.time.Duration;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "knowledge.document.ingestion")
@Validated
public class IngestionProperties {
    private boolean enabled = false;
    private String workerId = "knowledge-local";
    private Duration leaseDuration = Duration.ofSeconds(60);
    private Duration retryBaseDelay = Duration.ofSeconds(10);
    private int maxRetries = 5;
    private int scanBatchSize = 20;
    /** 后台入库的进程内并发上限，避免 OCR/Embedding 抢占在线检索资源。 */
    @Min(1)
    private int maxConcurrentTasks = 1;
    private String exchange = "knowledge.document";
    private String routingKey = "ingestion.wakeup";
    private String queue = "knowledge.document.ingestion";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getRetryBaseDelay() { return retryBaseDelay; }
    public void setRetryBaseDelay(Duration retryBaseDelay) { this.retryBaseDelay = retryBaseDelay; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getScanBatchSize() { return scanBatchSize; }
    public void setScanBatchSize(int scanBatchSize) { this.scanBatchSize = scanBatchSize; }
    public int getMaxConcurrentTasks() { return maxConcurrentTasks; }
    public void setMaxConcurrentTasks(int maxConcurrentTasks) { this.maxConcurrentTasks = maxConcurrentTasks; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }
    public String getQueue() { return queue; }
    public void setQueue(String queue) { this.queue = queue; }
}

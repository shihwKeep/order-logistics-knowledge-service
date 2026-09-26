package com.xjjk.knowledge.publication.release;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("knowledge.release")
public class ReleaseProperties {
    private boolean enabled;
    private String workerId = "knowledge-release-local";
    private Duration leaseDuration = Duration.ofMinutes(5);
    private Duration retryBaseDelay = Duration.ofSeconds(30);
    private int maxRetries = 5;
    private int scanBatchSize = 20;
    private String routingKey = "release.wakeup";
    private String queue = "knowledge.release";

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
    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }
    public String getQueue() { return queue; }
    public void setQueue(String queue) { this.queue = queue; }
}

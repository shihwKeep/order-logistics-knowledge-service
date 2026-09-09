package com.xjjk.knowledge.document.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时扫描是 MQ 丢失、服务重启和租约过期后的最终恢复通道。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.document.ingestion", name = "enabled", havingValue = "true")
public class IngestionTaskScanner {
    private final IngestionTaskRepository tasks;
    private final IngestionWorker worker;
    private final IngestionProperties properties;

    public IngestionTaskScanner(IngestionTaskRepository tasks, IngestionWorker worker, IngestionProperties properties) {
        this.tasks = tasks;
        this.worker = worker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${knowledge.document.ingestion.scan-delay:5000}")
    public void scan() {
        tasks.findDueTaskIds(properties.getScanBatchSize()).forEach(worker::process);
    }
}

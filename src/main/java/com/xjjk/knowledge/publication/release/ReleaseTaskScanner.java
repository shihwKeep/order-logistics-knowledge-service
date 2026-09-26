package com.xjjk.knowledge.publication.release;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "knowledge.release", name = "enabled", havingValue = "true")
public class ReleaseTaskScanner {
    private final ReleaseTaskRepository tasks;
    private final ReleaseWorker worker;
    private final ReleaseProperties properties;

    public ReleaseTaskScanner(
            ReleaseTaskRepository tasks, ReleaseWorker worker, ReleaseProperties properties) {
        this.tasks = tasks;
        this.worker = worker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${knowledge.release.scan-delay:5000}")
    public void scan() {
        tasks.findDueIds(properties.getScanBatchSize()).forEach(worker::process);
    }
}

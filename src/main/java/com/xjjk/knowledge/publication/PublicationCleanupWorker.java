package com.xjjk.knowledge.publication;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 发布指针切换后的旧索引异步清理；删除天然幂等，失败持久化为 RETRY。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.retrieval.publication-cleanup", name = "enabled", havingValue = "true")
public class PublicationCleanupWorker {
    private final PublicationMapper mapper;
    private final PublicationCleanupService cleanup;
    private final PublicationCleanupProperties properties;

    public PublicationCleanupWorker(
            PublicationMapper mapper, PublicationCleanupService cleanup, PublicationCleanupProperties properties) {
        this.mapper = mapper;
        this.cleanup = cleanup;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${knowledge.retrieval.publication-cleanup.scan-delay:5000}")
    public void scan() {
        mapper.findDueCleanup(properties.getBatchSize()).forEach(cleanup::process);
    }
}

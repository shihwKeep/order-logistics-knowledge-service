package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.retrieval.indexing.PublicationIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 发布指针切换后的旧索引异步清理；删除天然幂等，失败持久化为 RETRY。 */
@Component
@ConditionalOnProperty(prefix = "knowledge.retrieval.publication-cleanup", name = "enabled", havingValue = "true")
public class PublicationCleanupWorker {
    private static final Logger log = LoggerFactory.getLogger(PublicationCleanupWorker.class);
    private final PublicationMapper mapper;
    private final PublicationIndexService indexes;
    private final PublicationCleanupProperties properties;

    public PublicationCleanupWorker(
            PublicationMapper mapper, PublicationIndexService indexes, PublicationCleanupProperties properties) {
        this.mapper = mapper;
        this.indexes = indexes;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${knowledge.retrieval.publication-cleanup.scan-delay:5000}")
    public void scan() {
        mapper.findDueCleanup(properties.getBatchSize()).forEach(this::process);
    }

    void process(PublicationCleanupTask task) {
        try {
            indexes.deletePublished(task.tenantId(), task.documentId(), task.versionId());
            mapper.completeCleanup(task.id());
        } catch (RuntimeException exception) {
            mapper.failCleanup(task.id(), "PUBLISHED_INDEX_CLEANUP_FAILED");
            log.warn("knowledge_published_index_cleanup_failed taskId={}, exceptionType={}",
                    task.id(), exception.getClass().getSimpleName());
        }
    }
}

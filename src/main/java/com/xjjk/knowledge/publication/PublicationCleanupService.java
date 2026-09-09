package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.retrieval.indexing.PublicationIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在数据库文档行锁保护下清理旧发布索引。
 * 行锁会与发布、回滚事务串行化，防止 A→B 后回滚 A 时，遗留 cleanup(A) 删除当前线上版本。
 */
@Service
public class PublicationCleanupService {
    private static final Logger log = LoggerFactory.getLogger(PublicationCleanupService.class);

    private final PublicationMapper mapper;
    private final PublicationIndexService indexes;

    public PublicationCleanupService(PublicationMapper mapper, PublicationIndexService indexes) {
        this.mapper = mapper;
        this.indexes = indexes;
    }

    @Transactional
    public void process(PublicationCleanupTask task) {
        try {
            Long currentVersion = mapper.lockCurrentPublishedVersion(task.tenantId(), task.documentId());
            if (Long.valueOf(task.versionId()).equals(currentVersion)) {
                // 该版本已被回滚为当前版本，旧清理任务作废。
                mapper.completeCleanup(task.id());
                return;
            }
            indexes.deletePublished(task.tenantId(), task.documentId(), task.versionId());
            mapper.completeCleanup(task.id());
        } catch (RuntimeException exception) {
            mapper.failCleanup(task.id(), "PUBLISHED_INDEX_CLEANUP_FAILED");
            log.warn("knowledge_published_index_cleanup_failed taskId={}, exceptionType={}",
                    task.id(), exception.getClass().getSimpleName());
        }
    }
}

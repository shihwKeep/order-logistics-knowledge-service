package com.xjjk.knowledge.publication.cleanup;

import com.xjjk.knowledge.retrieval.index.KeywordIndex;
import com.xjjk.knowledge.retrieval.index.VectorIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 清理被替换或最终失败版本的 ES/Milvus 派生数据。
 * MySQL 任务是事实源，索引删除天然幂等，租约令牌阻止过期 Worker 回写任务状态。
 */
@Component
@ConditionalOnProperty(
        prefix = "knowledge.retrieval.derived-cleanup", name = "enabled", havingValue = "true")
public class DerivedCleanupWorker {
    private static final Logger log = LoggerFactory.getLogger(DerivedCleanupWorker.class);

    private final DerivedCleanupRepository repository;
    private final KeywordIndex keywordIndex;
    private final VectorIndex vectorIndex;
    private final DerivedCleanupProperties properties;

    public DerivedCleanupWorker(
            DerivedCleanupRepository repository,
            KeywordIndex keywordIndex,
            VectorIndex vectorIndex,
            DerivedCleanupProperties properties) {
        this.repository = repository;
        this.keywordIndex = keywordIndex;
        this.vectorIndex = vectorIndex;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${knowledge.retrieval.derived-cleanup.scan-delay:5000}")
    public void scan() {
        repository.findDueIds(properties.getBatchSize()).forEach(id ->
                repository.claim(id, properties.getWorkerId(), properties.getLeaseDuration())
                        .ifPresent(this::process));
    }

    public void process(DerivedCleanupTask task) {
        try {
            if (repository.isReferenced(task)) {
                // 该版本重新成为草稿或活动 Release 的成员，历史清理任务直接作废。
                repository.completeOwned(task.id(), task.leaseToken());
                return;
            }
            keywordIndex.deleteVersion(
                    task.layer(), task.tenantId(), task.documentId(), task.versionId());
            vectorIndex.deleteVersion(
                    task.layer(), task.tenantId(), task.documentId(), task.versionId());
            // 外部删除完成后再次检查，避免引用状态变化时错误宣告任务完成。
            if (repository.isReferenced(task)) {
                throw new IllegalStateException("清理期间版本重新被活动指针引用");
            }
            repository.completeOwned(task.id(), task.leaseToken());
        } catch (RuntimeException exception) {
            repository.retryOwned(
                    task, "DERIVED_INDEX_CLEANUP_FAILED",
                    properties.getMaxRetries(), properties.getRetryBaseDelay());
            log.warn("knowledge_derived_index_cleanup_failed taskId={}, layer={}, exceptionType={}",
                    task.id(), task.layer(), exception.getClass().getSimpleName());
        }
    }
}

package com.xjjk.knowledge.publication.release;

import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.retrieval.indexing.PublicationIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 外部正式索引先全部准备并校验，最后才用 MySQL 短事务切换活动 Release。 */
@Component
public class ReleaseWorker {
    private static final Logger log = LoggerFactory.getLogger(ReleaseWorker.class);

    private final ReleaseTaskRepository tasks;
    private final ReleaseRepository releases;
    private final PublicationIndexService indexes;
    private final ReleaseProperties properties;

    public ReleaseWorker(
            ReleaseTaskRepository tasks,
            ReleaseRepository releases,
            PublicationIndexService indexes,
            ReleaseProperties properties) {
        this.tasks = tasks;
        this.releases = releases;
        this.indexes = indexes;
        this.properties = properties;
    }

    public boolean process(long taskId) {
        var claimed = tasks.claim(taskId, properties.getWorkerId(), properties.getLeaseDuration());
        if (claimed.isEmpty()) {
            return false;
        }
        ReleaseTaskLease lease = claimed.get();
        KnowledgeRelease release = releases.find(
                        lease.tenantId(), lease.knowledgeBaseId(), lease.releaseId())
                .orElse(null);
        if (release == null) {
            tasks.retryOrFail(lease, "RELEASE_NOT_FOUND");
            return false;
        }
        if (release.status() != ReleaseStatus.PREPARING) {
            tasks.complete(lease);
            return release.status() == ReleaseStatus.ACTIVE;
        }
        try {
            for (DocumentVersion version : releases.changedVersions(release)) {
                if (!indexes.isPublishedReady(version)) {
                    indexes.preparePublished(version);
                }
                if (!tasks.renew(lease, properties.getLeaseDuration())) {
                    throw new IllegalStateException("Release 任务租约已经丢失");
                }
            }
            releases.activate(release, lease);
            tasks.complete(lease);
            return true;
        } catch (ReleaseConflictException conflict) {
            releases.markConflict(release, lease);
            tasks.complete(lease);
            return false;
        } catch (RuntimeException failure) {
            boolean exhausted = tasks.retryOrFail(lease, "RELEASE_PREPARE_FAILED");
            if (exhausted) {
                releases.markFailed(release, "RELEASE_PREPARE_FAILED");
            }
            log.warn("knowledge_release_prepare_failed releaseId={}, exceptionType={}",
                    release.id(), failure.getClass().getSimpleName());
            return false;
        }
    }
}

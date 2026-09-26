package com.xjjk.knowledge.publication.release;

import com.xjjk.knowledge.document.domain.DocumentVersion;
import java.util.List;
import java.util.Optional;

public interface ReleaseRepository {
    ReleaseBaseline lockBaseline(long tenantId, long knowledgeBaseId);

    List<ReleaseItem> items(long releaseId);

    Optional<ReleaseItem> findReadyItem(
            long tenantId, long knowledgeBaseId, long documentId, long versionId);

    Optional<KnowledgeRelease> findByRequest(long tenantId, String requestId);

    int nextReleaseNumber(long tenantId, long knowledgeBaseId);

    KnowledgeRelease insert(KnowledgeRelease release);

    void insertItems(long releaseId, List<ReleaseItem> items);

    void enqueue(KnowledgeRelease release);

    Optional<KnowledgeRelease> find(long tenantId, long knowledgeBaseId, long releaseId);

    List<DocumentVersion> changedVersions(KnowledgeRelease release);

    void activate(KnowledgeRelease release, ReleaseTaskLease lease);

    void markConflict(KnowledgeRelease release, ReleaseTaskLease lease);

    void markFailed(KnowledgeRelease release, String failureCode);

    Long currentReleaseId(long tenantId, long knowledgeBaseId);
}

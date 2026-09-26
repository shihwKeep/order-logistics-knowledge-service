package com.xjjk.knowledge.publication.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.retrieval.indexing.PublicationIndexService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReleaseWorkerTest {
    private ReleaseTaskRepository tasks;
    private ReleaseRepository releases;
    private PublicationIndexService indexes;
    private ReleaseWorker worker;
    private ReleaseTaskLease lease;
    private KnowledgeRelease release;

    @BeforeEach
    void setUp() {
        tasks = mock(ReleaseTaskRepository.class);
        releases = mock(ReleaseRepository.class);
        indexes = mock(PublicationIndexService.class);
        ReleaseProperties properties = new ReleaseProperties();
        worker = new ReleaseWorker(tasks, releases, indexes, properties);
        lease = new ReleaseTaskLease(70L, 51L, 1L, 7L, "lease-70");
        release = KnowledgeRelease.preparing(
                1L, 7L, 2, 50L, "release-2", "a".repeat(64), 6, 10567L)
                .withId(51L);
        when(tasks.claim(70L, properties.getWorkerId(), properties.getLeaseDuration()))
                .thenReturn(Optional.of(lease));
        when(tasks.renew(lease, properties.getLeaseDuration())).thenReturn(true);
        when(releases.find(1L, 7L, 51L)).thenReturn(Optional.of(release));
    }

    @Test
    void activatesOnlyAfterEveryChangedVersionIsPrepared() {
        DocumentVersion versionA = version(11L, 91L);
        DocumentVersion versionB = version(12L, 102L);
        when(releases.changedVersions(release)).thenReturn(List.of(versionA, versionB));
        assertThat(worker.process(70L)).isTrue();

        var order = inOrder(indexes, releases, tasks);
        order.verify(indexes).preparePublished(versionA);
        order.verify(indexes).preparePublished(versionB);
        order.verify(releases).activate(release, lease);
        order.verify(tasks).complete(lease);
    }

    @Test
    void keepsOldReleaseWhenSecondIndexPreparationFails() {
        DocumentVersion versionA = version(11L, 91L);
        DocumentVersion versionB = version(12L, 102L);
        when(releases.changedVersions(release)).thenReturn(List.of(versionA, versionB));
        doThrow(new IllegalStateException("Milvus unavailable"))
                .when(indexes).preparePublished(versionB);

        assertThat(worker.process(70L)).isFalse();

        verify(releases, never()).activate(release, lease);
        verify(tasks).retryOrFail(lease, "RELEASE_PREPARE_FAILED");
    }

    @Test
    void marksStaleBaseReleaseAsConflict() {
        when(releases.changedVersions(release)).thenReturn(List.of());
        doThrow(new ReleaseConflictException()).when(releases).activate(release, lease);

        assertThat(worker.process(70L)).isFalse();

        verify(releases).markConflict(release, lease);
        verify(tasks).complete(lease);
    }

    @Test
    void reusesPublishedIndexesWhenBothStoresMatchMysqlManifest() {
        DocumentVersion version = version(11L, 91L);
        when(releases.changedVersions(release)).thenReturn(List.of(version));
        when(indexes.isPublishedReady(version)).thenReturn(true);

        assertThat(worker.process(70L)).isTrue();

        verify(indexes, never()).preparePublished(version);
        verify(releases).activate(release, lease);
        verify(tasks).complete(lease);
    }

    private static DocumentVersion version(long documentId, long versionId) {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                versionId, 1L, 7L, documentId, 2, DocumentStatus.READY,
                "rule.pdf", "pdf", "application/pdf", 100L,
                "a".repeat(64), "source", null, "pdf-v1", "structural-v1",
                "qwen3.7-text-embedding", 2560, "qwen37-customer-service-v2",
                "b".repeat(64), now, false, 0, 1, 1,
                null, null, null, 10567L, now, now);
    }
}

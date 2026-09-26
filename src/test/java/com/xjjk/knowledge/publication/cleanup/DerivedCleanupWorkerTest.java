package com.xjjk.knowledge.publication.cleanup;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xjjk.knowledge.retrieval.index.KeywordIndex;
import com.xjjk.knowledge.retrieval.index.VectorIndex;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DerivedCleanupWorkerTest {
    private DerivedCleanupRepository repository;
    private KeywordIndex keywordIndex;
    private VectorIndex vectorIndex;
    private DerivedCleanupWorker worker;
    private DerivedCleanupTask task;

    @BeforeEach
    void setUp() {
        repository = mock(DerivedCleanupRepository.class);
        keywordIndex = mock(KeywordIndex.class);
        vectorIndex = mock(VectorIndex.class);
        DerivedCleanupProperties properties = new DerivedCleanupProperties();
        properties.setMaxRetries(4);
        properties.setRetryBaseDelay(Duration.ofSeconds(10));
        worker = new DerivedCleanupWorker(repository, keywordIndex, vectorIndex, properties);
        task = new DerivedCleanupTask(7L, 1L, 2L, 8L, 13L,
                IndexLayer.DRAFT, "DRAFT_REPLACED", 0, "lease-token");
    }

    @Test
    void neverDeletesCurrentDraftOrCurrentReleaseVersion() {
        when(repository.isReferenced(task)).thenReturn(true);

        worker.process(task);

        verify(keywordIndex, never()).deleteVersion(
                IndexLayer.DRAFT, 1L, 8L, 13L);
        verify(vectorIndex, never()).deleteVersion(
                IndexLayer.DRAFT, 1L, 8L, 13L);
        verify(repository).completeOwned(7L, "lease-token");
    }

    @Test
    void retriesWhenOneDerivedStoreFails() {
        when(repository.isReferenced(task)).thenReturn(false);
        doThrow(new IllegalStateException("ES unavailable"))
                .when(keywordIndex).deleteVersion(IndexLayer.DRAFT, 1L, 8L, 13L);

        worker.process(task);

        verify(repository).retryOwned(
                task, "DERIVED_INDEX_CLEANUP_FAILED", 4, Duration.ofSeconds(10));
        verify(vectorIndex, never()).deleteVersion(
                IndexLayer.DRAFT, 1L, 8L, 13L);
    }

    @Test
    void deletesBothIndexesAndRechecksReferencesBeforeCompleting() {
        when(repository.isReferenced(task)).thenReturn(false, false);

        worker.process(task);

        verify(keywordIndex).deleteVersion(IndexLayer.DRAFT, 1L, 8L, 13L);
        verify(vectorIndex).deleteVersion(IndexLayer.DRAFT, 1L, 8L, 13L);
        verify(repository).completeOwned(7L, "lease-token");
    }
}

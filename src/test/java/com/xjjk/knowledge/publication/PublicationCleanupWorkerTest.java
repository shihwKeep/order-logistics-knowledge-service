package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.retrieval.indexing.PublicationIndexService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PublicationCleanupWorkerTest {
    @Test
    void recordsRetryWhenOldPublishedIndexDeletionFails() {
        PublicationMapper mapper = mock(PublicationMapper.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        PublicationCleanupTask task = new PublicationCleanupTask(7L, 1L, 3L, 4L, 0);
        doThrow(new IllegalStateException("es down")).when(indexes).deletePublished(1L, 3L, 4L);
        PublicationCleanupWorker worker = new PublicationCleanupWorker(
                mapper, indexes, new PublicationCleanupProperties());

        worker.process(task);

        verify(mapper).failCleanup(7L, "PUBLISHED_INDEX_CLEANUP_FAILED");
    }
}

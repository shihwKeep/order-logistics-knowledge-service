package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.retrieval.indexing.PublicationIndexService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicationCleanupWorkerTest {
    @Test
    void skipsDeletionWhenVersionHasBecomeCurrentAgain() {
        PublicationMapper mapper = mock(PublicationMapper.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        PublicationCleanupTask task = new PublicationCleanupTask(7L, 1L, 3L, 4L, 0);
        when(mapper.lockCurrentPublishedVersion(1L, 3L)).thenReturn(4L);
        PublicationCleanupService service = new PublicationCleanupService(mapper, indexes);

        service.process(task);

        verify(indexes, never()).deletePublished(1L, 3L, 4L);
        verify(mapper).completeCleanup(7L);
    }

    @Test
    void deletesOnlyNonCurrentVersionAndPersistsRetryOnFailure() {
        PublicationMapper mapper = mock(PublicationMapper.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        PublicationCleanupTask task = new PublicationCleanupTask(8L, 1L, 3L, 4L, 0);
        when(mapper.lockCurrentPublishedVersion(1L, 3L)).thenReturn(5L);
        doThrow(new IllegalStateException("es down"))
                .when(indexes).deletePublished(1L, 3L, 4L);

        new PublicationCleanupService(mapper, indexes).process(task);

        verify(indexes).deletePublished(1L, 3L, 4L);
        verify(mapper).failCleanup(8L, "PUBLISHED_INDEX_CLEANUP_FAILED");
    }
}

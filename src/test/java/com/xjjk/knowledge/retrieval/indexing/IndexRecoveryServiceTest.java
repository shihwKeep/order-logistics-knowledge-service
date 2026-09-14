package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexRecoveryServiceTest {

    @Test
    void rebuildsEveryCurrentDraftAndPublishedTargetWithoutChangingMysqlPointers() {
        IndexRecoveryRepository repository = mock(IndexRecoveryRepository.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        DocumentVersion published = version(4L, DocumentStatus.PUBLISHED);
        DocumentVersion draft = version(5L, DocumentStatus.READY);
        when(repository.listCurrentTargets()).thenReturn(List.of(
                new IndexRecoveryTarget(IndexLayer.PUBLISHED, published),
                new IndexRecoveryTarget(IndexLayer.DRAFT, draft)));

        IndexRecoveryResult result = new IndexRecoveryService(repository, indexes).rebuildAll();

        var order = inOrder(indexes);
        order.verify(indexes).preparePublished(published);
        order.verify(indexes).prepareDraft(draft);
        assertThat(result).isEqualTo(new IndexRecoveryResult(1, 1));
    }

    private DocumentVersion version(long id, DocumentStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                id, 1L, 2L, 3L, 1, status, "refund.txt", "txt", "text/plain", 10L,
                "source", "key", null, "text-v1", "structural-v1", "qwen3.7-text-embedding",
                2560, "query-document-v1", "manifest", now, false, 0, 1, 1,
                null, null, null, 10567L, now, now);
    }
}

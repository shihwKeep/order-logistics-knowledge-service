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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class IndexRecoveryServiceTest {

    @Test
    void rebuildsEveryCurrentTargetThenUpgradesEmbeddingContractOncePerVersion() {
        IndexRecoveryRepository repository = mock(IndexRecoveryRepository.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        DocumentVersion published = oldVersion(4L, DocumentStatus.PUBLISHED);
        DocumentVersion draft = version(5L, DocumentStatus.READY);
        when(repository.listCurrentTargets()).thenReturn(List.of(
                new IndexRecoveryTarget(IndexLayer.PUBLISHED, published),
                new IndexRecoveryTarget(IndexLayer.DRAFT, draft)));
        when(repository.upgradeEmbeddingContract(published)).thenReturn(true);
        when(repository.upgradeEmbeddingContract(draft)).thenReturn(true);

        IndexRecoveryResult result = new IndexRecoveryService(repository, indexes).rebuildAll();

        var order = inOrder(indexes);
        order.verify(indexes).recoverPublished(published);
        order.verify(indexes).recoverDraft(draft);
        verify(repository).upgradeEmbeddingContract(published);
        verify(repository).upgradeEmbeddingContract(draft);
        assertThat(result).isEqualTo(new IndexRecoveryResult(1, 1));
    }

    @Test
    void upgradesSharedDraftAndPublishedVersionOnlyAfterBothLayersSucceed() {
        IndexRecoveryRepository repository = mock(IndexRecoveryRepository.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        DocumentVersion shared = oldVersion(4L, DocumentStatus.PUBLISHED);
        when(repository.listCurrentTargets()).thenReturn(List.of(
                new IndexRecoveryTarget(IndexLayer.PUBLISHED, shared),
                new IndexRecoveryTarget(IndexLayer.DRAFT, shared)));
        when(repository.upgradeEmbeddingContract(shared)).thenReturn(true);

        new IndexRecoveryService(repository, indexes).rebuildAll();

        var order = inOrder(indexes, repository);
        order.verify(indexes).recoverPublished(shared);
        order.verify(indexes).recoverDraft(shared);
        order.verify(repository).upgradeEmbeddingContract(shared);
    }

    @Test
    void doesNotUpgradeMysqlContractWhenAnyExternalIndexBuildFails() {
        IndexRecoveryRepository repository = mock(IndexRecoveryRepository.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        DocumentVersion published = oldVersion(4L, DocumentStatus.PUBLISHED);
        when(repository.listCurrentTargets()).thenReturn(List.of(
                new IndexRecoveryTarget(IndexLayer.PUBLISHED, published)));
        doThrow(new IllegalStateException("milvus failed"))
                .when(indexes).recoverPublished(published);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new IndexRecoveryService(repository, indexes).rebuildAll())
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).upgradeEmbeddingContract(published);
    }

    private DocumentVersion version(long id, DocumentStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                id, 1L, 2L, 3L, 1, status, "refund.txt", "txt", "text/plain", 10L,
                "source", "key", null, "text-v1", "structural-v1", "qwen3.7-text-embedding",
                2560, "query-document-v1", "manifest", now, false, 0, 1, 1,
                null, null, null, 10567L, now, now);
    }

    private DocumentVersion oldVersion(long id, DocumentStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                id, 1L, 2L, 3L, 1, status, "refund.txt", "txt", "text/plain", 10L,
                "source", "key", null, "text-v1", "structural-v1", "qwen3-embedding:4b-q4_K_M",
                2560, "qwen3-customer-service-v1", "manifest", now, false, 0, 1, 1,
                null, null, null, 10567L, now, now);
    }
}

package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.audit.AuditService;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.retrieval.indexing.PublicationIndexService;
import com.xjjk.knowledge.tenant.TenantAccessGuard;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicationServiceTest {

    @Test
    void preparesPublishedIndexesBeforeAtomicallySwitchingPointer() {
        PublicationRepository repository = mock(PublicationRepository.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        PublicationTarget target = target(DocumentStatus.READY, null);
        PublicationRecord record = record(PublicationAction.PUBLISH, null, 4L);
        when(repository.findByRequest(1L, "publish-1")).thenReturn(Optional.empty());
        when(repository.loadVersionTarget(1L, 2L, 3L, 4L)).thenReturn(target);
        when(repository.activate(target, PublicationAction.PUBLISH, 10567L, "publish-1")).thenReturn(record);
        PublicationService service = service(repository, indexes);

        PublicationRecord result = service.publish(principal(), 1L, 2L, 3L, 4L, "publish-1");

        assertThat(result).isEqualTo(record);
        InOrder order = inOrder(indexes, repository);
        order.verify(indexes).preparePublished(target.version());
        order.verify(repository).activate(target, PublicationAction.PUBLISH, 10567L, "publish-1");
    }

    @Test
    void indexFailureLeavesPointerTransactionUntouched() {
        PublicationRepository repository = mock(PublicationRepository.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        PublicationTarget target = target(DocumentStatus.READY, 8L);
        when(repository.findByRequest(1L, "publish-2")).thenReturn(Optional.empty());
        when(repository.loadVersionTarget(1L, 2L, 3L, 4L)).thenReturn(target);
        org.mockito.Mockito.doThrow(new IllegalStateException("milvus down"))
                .when(indexes).preparePublished(target.version());
        PublicationService service = service(repository, indexes);

        assertThatThrownBy(() -> service.publish(principal(), 1L, 2L, 3L, 4L, "publish-2"))
                .hasMessageContaining("milvus");
        verify(repository, never()).activate(any(), any(), anyLong(), any());
    }

    @Test
    void repeatedRequestReturnsExistingRecordWithoutReindexing() {
        PublicationRepository repository = mock(PublicationRepository.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        PublicationRecord existing = record(PublicationAction.PUBLISH, null, 4L);
        when(repository.findByRequest(1L, "same-request")).thenReturn(Optional.of(existing));

        PublicationRecord result = service(repository, indexes)
                .publish(principal(), 1L, 2L, 3L, 4L, "same-request");

        assertThat(result).isEqualTo(existing);
        verify(indexes, never()).preparePublished(any());
    }

    @Test
    void rollbackUsesSamePrepareThenSwitchProtocol() {
        PublicationRepository repository = mock(PublicationRepository.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        PublicationTarget target = target(DocumentStatus.ARCHIVED, 8L);
        PublicationRecord record = record(PublicationAction.ROLLBACK, 8L, 4L);
        when(repository.findByRequest(1L, "rollback-1")).thenReturn(Optional.empty());
        when(repository.loadVersionTarget(1L, 2L, 3L, 4L)).thenReturn(target);
        when(repository.activate(target, PublicationAction.ROLLBACK, 10567L, "rollback-1")).thenReturn(record);

        service(repository, indexes).rollback(principal(), 1L, 2L, 3L, 4L, "rollback-1");

        InOrder order = inOrder(indexes, repository);
        order.verify(indexes).preparePublished(target.version());
        order.verify(repository).activate(target, PublicationAction.ROLLBACK, 10567L, "rollback-1");
    }

    @Test
    void disableClearsPointerThroughRepositoryWhichRegistersDurableCleanup() {
        PublicationRepository repository = mock(PublicationRepository.class);
        PublicationIndexService indexes = mock(PublicationIndexService.class);
        PublicationTarget target = target(DocumentStatus.PUBLISHED, 4L);
        PublicationRecord record = record(PublicationAction.DISABLE, 4L, null);
        when(repository.findByRequest(1L, "disable-1")).thenReturn(Optional.empty());
        when(repository.loadCurrentPublishedTarget(1L, 2L, 3L)).thenReturn(target);
        when(repository.disable(target, 10567L, "disable-1")).thenReturn(record);

        PublicationRecord result = service(repository, indexes)
                .disable(principal(), 1L, 2L, 3L, "disable-1");

        assertThat(result).isEqualTo(record);
        verify(repository).disable(target, 10567L, "disable-1");
        verify(indexes, never()).deletePublished(anyLong(), anyLong(), anyLong());
    }

    private PublicationService service(PublicationRepository repository, PublicationIndexService indexes) {
        return new PublicationService(
                new TenantAccessGuard(), repository, indexes, mock(AuditService.class));
    }

    private AdminPrincipal principal() {
        return new AdminPrincipal(10567L, "74680", "石海文", 1L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
    }

    private PublicationTarget target(DocumentStatus status, Long currentPublished) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocument document = new KnowledgeDocument(
                3L, 1L, 2L, "退款规则", 4L, currentPublished, 1L, 1L, 2, now, now);
        DocumentVersion version = new DocumentVersion(
                4L, 1L, 2L, 3L, 1, status, "refund.txt", "txt", "text/plain", 10L,
                "source", "key", null, "text-v1", "structural-v1", "qwen", 2560,
                "instruction-v1", "manifest", now, false, 0, 1, 1,
                null, null, null, 10567L, now, now);
        return new PublicationTarget(document, version);
    }

    private PublicationRecord record(PublicationAction action, Long fromVersionId, Long toVersionId) {
        return new PublicationRecord(
                11L, 1L, 2L, 3L, fromVersionId, toVersionId, action,
                10567L, "request", 1, "manifest", LocalDateTime.now());
    }
}

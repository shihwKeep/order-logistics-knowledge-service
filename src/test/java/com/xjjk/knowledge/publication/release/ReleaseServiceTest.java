package com.xjjk.knowledge.publication.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.knowledgebase.service.KnowledgeBaseService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReleaseServiceTest {
    private ReleaseRepository repository;
    private ReleaseService service;
    private AdminPrincipal principal;

    @BeforeEach
    void setUp() {
        repository = mock(ReleaseRepository.class);
        service = new ReleaseService(mock(KnowledgeBaseService.class), repository);
        principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
    }

    @Test
    void createsFullSnapshotWhileReplacingOnlySelectedDocuments() {
        when(repository.lockBaseline(1L, 7L))
                .thenReturn(new ReleaseBaseline(50L, 6, "old-manifest"));
        when(repository.items(50L)).thenReturn(List.of(
                item(50L, 11L, 91L, "a"),
                item(50L, 12L, 92L, "b"),
                item(50L, 13L, 88L, "c")));
        when(repository.findReadyItem(1L, 7L, 12L, 102L))
                .thenReturn(Optional.of(item(0L, 12L, 102L, "d")));
        when(repository.nextReleaseNumber(1L, 7L)).thenReturn(2);
        when(repository.insert(any())).thenAnswer(invocation ->
                invocation.<KnowledgeRelease>getArgument(0).withId(51L));

        KnowledgeRelease release = service.create(
                principal, 1L, 7L,
                List.of(new ReleaseReplacement(12L, 102L)), "release-2");

        assertThat(release.id()).isEqualTo(51L);
        assertThat(release.baseReleaseId()).isEqualTo(50L);
        assertThat(release.status()).isEqualTo(ReleaseStatus.PREPARING);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReleaseItem>> items = ArgumentCaptor.forClass(List.class);
        verify(repository).insertItems(org.mockito.ArgumentMatchers.eq(51L), items.capture());
        assertThat(items.getValue())
                .extracting(ReleaseItem::documentId, ReleaseItem::versionId)
                .containsExactlyInAnyOrder(
                        tuple(11L, 91L), tuple(12L, 102L), tuple(13L, 88L));
        verify(repository).enqueue(release);
    }

    @Test
    void rejectsReleaseWhoseManifestMatchesCurrentRelease() {
        List<ReleaseItem> current = List.of(item(50L, 11L, 91L, "a"));
        String manifest = ReleaseService.manifestSha256(current);
        when(repository.lockBaseline(1L, 7L))
                .thenReturn(new ReleaseBaseline(50L, 6, manifest));
        when(repository.items(50L)).thenReturn(current);
        when(repository.findReadyItem(1L, 7L, 11L, 91L))
                .thenReturn(Optional.of(item(0L, 11L, 91L, "a")));
        when(repository.findByRequest(1L, "no-change")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(
                principal, 1L, 7L,
                List.of(new ReleaseReplacement(11L, 91L)), "no-change"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ApiErrorCode.RELEASE_NO_CHANGES));
    }

    @Test
    void rejectsChangedReuseOfRequestId() {
        when(repository.lockBaseline(1L, 7L))
                .thenReturn(new ReleaseBaseline(50L, 6, "old-manifest"));
        when(repository.items(50L)).thenReturn(List.of(item(50L, 11L, 91L, "a")));
        when(repository.findReadyItem(1L, 7L, 11L, 102L))
                .thenReturn(Optional.of(item(0L, 11L, 102L, "b")));
        when(repository.findByRequest(1L, "request-a"))
                .thenReturn(Optional.of(KnowledgeRelease.preparing(
                        1L, 7L, 2, 50L, "request-a", "different-manifest", 6, 10567L)
                        .withId(51L)));

        assertThatThrownBy(() -> service.create(
                principal, 1L, 7L,
                List.of(new ReleaseReplacement(11L, 102L)), "request-a"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void returnsExistingReleaseWhenSameRequestIsRetriedAfterActivation() {
        List<ReleaseItem> activeItems = List.of(item(51L, 11L, 102L, "b"));
        KnowledgeRelease existing = KnowledgeRelease.preparing(
                1L, 7L, 2, 50L, "request-a",
                ReleaseService.manifestSha256(activeItems), 6, 10567L)
                .withId(51L);
        when(repository.lockBaseline(1L, 7L))
                .thenReturn(new ReleaseBaseline(51L, 7, existing.manifestSha256()));
        when(repository.findByRequest(1L, "request-a")).thenReturn(Optional.of(existing));
        when(repository.items(51L)).thenReturn(activeItems);

        KnowledgeRelease retried = service.create(
                principal, 1L, 7L,
                List.of(new ReleaseReplacement(11L, 102L)), "request-a");

        assertThat(retried).isEqualTo(existing);
        verify(repository, never()).findReadyItem(1L, 7L, 11L, 102L);
        verify(repository, never()).insert(any());
    }

    private static ReleaseItem item(long releaseId, long documentId, long versionId, String manifest) {
        return new ReleaseItem(releaseId, 1L, 7L, documentId, versionId, manifest.repeat(64));
    }
}

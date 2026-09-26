package com.xjjk.knowledge.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.publication.release.KnowledgeRelease;
import com.xjjk.knowledge.publication.release.ReleaseService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicationServiceTest {
    private ReleaseService releases;
    private PublicationService service;
    private AdminPrincipal principal;

    @BeforeEach
    void setUp() {
        releases = mock(ReleaseService.class);
        service = new PublicationService(releases);
        principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L,
                Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
    }

    @Test
    void publishDelegatesToReleaseWithoutWritingIndexesOrPointers() {
        KnowledgeRelease expected = release(51L);
        when(releases.publishDocument(principal, 1L, 7L, 11L, 91L, "publish-1"))
                .thenReturn(expected);

        KnowledgeRelease actual = service.publish(
                principal, 1L, 7L, 11L, 91L, "publish-1");

        assertThat(actual).isEqualTo(expected);
        verify(releases).publishDocument(principal, 1L, 7L, 11L, 91L, "publish-1");
    }

    @Test
    void rollbackDelegatesToReleaseManifestReplacement() {
        KnowledgeRelease expected = release(52L);
        when(releases.rollbackDocument(principal, 1L, 7L, 11L, 80L, "rollback-1"))
                .thenReturn(expected);

        assertThat(service.rollback(principal, 1L, 7L, 11L, 80L, "rollback-1"))
                .isEqualTo(expected);
        verify(releases).rollbackDocument(principal, 1L, 7L, 11L, 80L, "rollback-1");
    }

    @Test
    void disableDelegatesToReleaseManifestRemoval() {
        KnowledgeRelease expected = release(53L);
        when(releases.disableDocument(principal, 1L, 7L, 11L, "disable-1"))
                .thenReturn(expected);

        assertThat(service.disable(principal, 1L, 7L, 11L, "disable-1"))
                .isEqualTo(expected);
        verify(releases).disableDocument(principal, 1L, 7L, 11L, "disable-1");
    }

    private KnowledgeRelease release(long id) {
        return KnowledgeRelease.preparing(
                1L, 7L, 2, 50L, "request-" + id,
                "a".repeat(64), 6, 10567L).withId(id);
    }
}

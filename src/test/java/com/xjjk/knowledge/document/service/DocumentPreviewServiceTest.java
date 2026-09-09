package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.storage.SourceObjectStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentPreviewServiceTest {
    @Test
    void authorizesVersionAndReadsOnlyItsServerGeneratedObjectKey() throws Exception {
        DocumentQueryService queries = mock(DocumentQueryService.class);
        SourceObjectStore objects = mock(SourceObjectStore.class);
        DocumentVersion version = version();
        when(queries.requireVersion(principal(), 1L, 2L, 3L, 4L)).thenReturn(version);
        when(objects.get("tenant/1/knowledge-base/2/document/3/version/4/source"))
                .thenReturn(new ByteArrayInputStream("pdf".getBytes()));
        DocumentPreviewService service = new DocumentPreviewService(queries, objects);

        try (DocumentPreview preview = service.source(principal(), 1L, 2L, 3L, 4L)) {
            assertThat(preview.filename()).isEqualTo("refund.pdf");
            assertThat(preview.contentType()).isEqualTo("application/pdf");
            assertThat(preview.inline()).isTrue();
            assertThat(preview.content().readAllBytes()).isEqualTo("pdf".getBytes());
        }
        verify(objects).get("tenant/1/knowledge-base/2/document/3/version/4/source");
    }

    private AdminPrincipal principal() {
        return new AdminPrincipal(10567L, "74680", "石海文", 1L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
    }

    private DocumentVersion version() {
        LocalDateTime now = LocalDateTime.now();
        return new DocumentVersion(
                4L, 1L, 2L, 3L, 1, DocumentStatus.READY, "refund.pdf", "pdf", "application/pdf", 10L,
                "sha", "tenant/1/knowledge-base/2/document/3/version/4/source", null,
                "parser", "chunker", "embedding", 2560, "instruction", "manifest", now,
                false, 0, 1, 1, null, null, null, 10567L, now, now);
    }
}

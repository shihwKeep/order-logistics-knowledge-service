package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.persistence.DocumentRepository;
import com.xjjk.knowledge.knowledgebase.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentQueryServiceTest {
    @Test
    void validatesVersionBeforeReturningBoundedChunks() {
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentManagementRepository management = mock(DocumentManagementRepository.class);
        DocumentVersion version = version();
        when(documents.findDocument(1L, 3L)).thenReturn(Optional.of(document()));
        when(documents.findVersion(1L, 3L, 4L)).thenReturn(Optional.of(version));
        DocumentChunkView chunk = new DocumentChunkView(
                8L, 4L, 0, "退款规则", "正文", 12, "{\"pageNumber\":1}", LocalDateTime.now());
        when(management.listChunks(1L, 3L, 4L, 0, 100)).thenReturn(List.of(chunk));
        DocumentQueryService service = new DocumentQueryService(knowledgeBases, documents, management);

        List<DocumentChunkView> result = service.chunks(principal(), 1L, 2L, 3L, 4L, -1, 500);

        assertThat(result).containsExactly(chunk);
        verify(management).listChunks(1L, 3L, 4L, 0, 100);
    }

    private AdminPrincipal principal() {
        return new AdminPrincipal(10567L, "74680", "石海文", 1L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
    }

    private DocumentVersion version() {
        return new DocumentVersion(
                4L, 1L, 2L, 3L, 1,
                com.xjjk.knowledge.document.domain.DocumentStatus.READY,
                "refund.pdf", "pdf", "application/pdf", 10L, "sha", "source", null,
                "parser", "chunker", "embedding", 2560, "instruction", "manifest",
                LocalDateTime.now(), false, 0, 1, 1, null, null, null,
                10567L, LocalDateTime.now(), LocalDateTime.now());
    }

    private com.xjjk.knowledge.document.domain.KnowledgeDocument document() {
        LocalDateTime now = LocalDateTime.now();
        return new com.xjjk.knowledge.document.domain.KnowledgeDocument(
                3L, 1L, 2L, "退款规则", 4L, null, 10567L, 10567L, 0, now, now);
    }
}

package com.xjjk.knowledge.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xjjk.knowledge.audit.AuditService;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.persistence.DocumentRepository;
import com.xjjk.knowledge.knowledgebase.service.KnowledgeBaseService;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentCorrectionServiceTest {
    @Test
    void delegatesAtomicCorrectionWithoutReplacingRawTextAndWritesAudit() {
        KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentManagementRepository management = mock(DocumentManagementRepository.class);
        AuditService audit = mock(AuditService.class);
        AdminPrincipal principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
        LocalDateTime now = LocalDateTime.now();
        when(documents.findDocument(1L, 3L)).thenReturn(Optional.of(
                new KnowledgeDocument(3L, 1L, 2L, "退款规则", 4L, null, 1L, 1L, 0, now, now)));
        when(documents.findVersion(1L, 3L, 4L)).thenReturn(Optional.of(new DocumentVersion(
                4L, 1L, 2L, 3L, 1, DocumentStatus.CHUNKING, "refund.pdf", "pdf", "application/pdf",
                10L, "sha", "key", null, "pdf-v1", "structural-v1", true, 0, 1, 0,
                null, null, null, 10567L, now, now)));
        DocumentUnit corrected = new DocumentUnit(
                9L, 1L, 3L, 4L, "PAGE", 1, "第 1 页", "退款",
                "OCR原文", "校正后的规则", 0.51, true, 1);
        when(management.correctUnit(1L, 2L, 3L, 4L, 9L, "校正后的规则", 10567L, "req-1"))
                .thenReturn(corrected);
        DocumentCorrectionService service = new DocumentCorrectionService(
                knowledgeBases, documents, management, audit);

        DocumentUnit result = service.correct(principal, 1L, 2L, 3L, 4L, 9L, "校正后的规则", "req-1");

        assertThat(result.rawText()).isEqualTo("OCR原文");
        assertThat(result.effectiveText()).isEqualTo("校正后的规则");
        verify(management).correctUnit(1L, 2L, 3L, 4L, 9L, "校正后的规则", 10567L, "req-1");
        verify(audit).success(
                1L, principal, com.xjjk.knowledge.audit.AuditAction.DOCUMENT_UNIT_CORRECT,
                "DOCUMENT_UNIT", "9", "req-1", java.util.Map.of("versionId", 4L));
    }
}

package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.audit.AuditAction;
import com.xjjk.knowledge.audit.AuditService;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.domain.SourceFile;
import com.xjjk.knowledge.document.persistence.DocumentRepository;
import com.xjjk.knowledge.document.storage.SourceObjectStore;
import com.xjjk.knowledge.document.storage.UploadPolicy;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBase;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBaseStatus;
import com.xjjk.knowledge.knowledgebase.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentUploadServiceTest {

    private KnowledgeBaseService knowledgeBaseService;
    private DocumentRepository repository;
    private SourceObjectStore objectStore;
    private AuditService auditService;
    private DocumentUploadService service;
    private AdminPrincipal principal;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        repository = mock(DocumentRepository.class);
        objectStore = mock(SourceObjectStore.class);
        auditService = mock(AuditService.class);
        service = new DocumentUploadService(
                knowledgeBaseService,
                repository,
                objectStore,
                new UploadPolicy(1024 * 1024),
                auditService);
        principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L,
                Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
    }

    @Test
    void validatesOwnershipRegistersVersionStoresSourceAndAudits() {
        byte[] content = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);
        OffsetDateTime now = OffsetDateTime.parse("2026-09-09T19:00:00+08:00");
        when(knowledgeBaseService.get(principal, 1L, 10L)).thenReturn(new KnowledgeBase(
                10L, 1L, "售后规则", null, KnowledgeBaseStatus.ENABLED, 0, now, now));
        CreatedDocument created = createdDocument(sha256(content));
        when(repository.createDocument(
                eq(1L), eq(10L), eq(10567L), eq("退款政策"), any(SourceFile.class), eq("req-upload")))
                .thenReturn(created);

        CreatedDocument result = service.uploadNewDocument(
                principal,
                1L,
                10L,
                "退款政策",
                "refund.pdf",
                "application/pdf",
                content,
                "req-upload");

        assertThat(result).isEqualTo(created);
        InOrder order = inOrder(knowledgeBaseService, repository, objectStore, auditService);
        order.verify(knowledgeBaseService).get(principal, 1L, 10L);
        order.verify(repository).createDocument(
                eq(1L), eq(10L), eq(10567L), eq("退款政策"), any(SourceFile.class), eq("req-upload"));
        order.verify(objectStore).put(
                eq(created.version().sourceObjectKey()),
                any(InputStream.class),
                eq((long) content.length),
                eq("application/pdf"));
        order.verify(auditService).success(
                1L,
                principal,
                AuditAction.DOCUMENT_UPLOAD,
                "DOCUMENT",
                Long.toString(created.document().id()),
                "req-upload",
                Map.of("documentTitle", "退款政策", "versionId", 21L));
    }

    @Test
    void derivesTitleFromFilenameWhenTitleIsBlank() {
        byte[] content = "知识内容".getBytes(StandardCharsets.UTF_8);
        when(knowledgeBaseService.get(principal, 1L, 10L)).thenReturn(mock(KnowledgeBase.class));
        when(repository.createDocument(
                eq(1L), eq(10L), eq(10567L), eq("客服规范"), any(SourceFile.class), eq("req-title")))
                .thenReturn(createdDocument());

        service.uploadNewDocument(
                principal, 1L, 10L, " ", "客服规范.txt", "text/plain", content, "req-title");

        verify(repository).createDocument(
                eq(1L), eq(10L), eq(10567L), eq("客服规范"), any(SourceFile.class), eq("req-title"));
    }

    @Test
    void uploadsNewVersionForExistingDocument() {
        byte[] content = "%PDF-1.7\nsecond".getBytes(StandardCharsets.US_ASCII);
        when(knowledgeBaseService.get(principal, 1L, 10L)).thenReturn(mock(KnowledgeBase.class));
        CreatedDocument created = createdDocument();
        when(repository.createVersion(
                eq(1L), eq(10L), eq(13L), eq(10567L), any(SourceFile.class), eq("req-v2")))
                .thenReturn(created);

        CreatedDocument result = service.uploadNewVersion(
                principal, 1L, 10L, 13L,
                "refund-v2.pdf", "application/pdf", content, "req-v2");

        assertThat(result).isEqualTo(created);
        verify(repository).createVersion(
                eq(1L), eq(10L), eq(13L), eq(10567L), any(SourceFile.class), eq("req-v2"));
        verify(objectStore).put(
                eq(created.version().sourceObjectKey()), any(InputStream.class),
                eq((long) content.length), eq("application/pdf"));
    }

    @Test
    void idempotentRetryDoesNotWriteSourceObjectAgain() {
        byte[] content = "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII);
        CreatedDocument created = createdDocument(sha256(content));
        when(knowledgeBaseService.get(principal, 1L, 10L)).thenReturn(mock(KnowledgeBase.class));
        when(repository.findByUploadRequest(1L, "req-existing")).thenReturn(java.util.Optional.of(created));

        CreatedDocument result = service.uploadNewVersion(
                principal, 1L, 10L, 13L,
                "refund.pdf", "application/pdf", content, "req-existing");

        assertThat(result).isEqualTo(created);
        verify(repository, never()).createVersion(anyLong(), anyLong(), anyLong(),
                anyLong(), any(SourceFile.class), any(String.class));
        verify(objectStore, never()).put(any(String.class), any(InputStream.class), anyLong(), any(String.class));
    }

    private CreatedDocument createdDocument() {
        return createdDocument("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private CreatedDocument createdDocument(String sourceSha256) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 9, 19, 0);
        KnowledgeDocument document = new KnowledgeDocument(
                13L, 1L, 10L, "退款政策", null, null,
                10567L, 10567L, 0, now, now);
        DocumentVersion version = new DocumentVersion(
                21L, 1L, 10L, 13L, 1, DocumentStatus.UPLOADED,
                "refund.pdf", "pdf", "application/pdf", 16L,
                sourceSha256,
                "tenant/1/knowledge-base/10/document/13/version/21/source",
                null, null, null, null, null, null, null, null, false, 0, 0, 0,
                null, null, null, 10567L, now, now);
        return new CreatedDocument(document, version);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

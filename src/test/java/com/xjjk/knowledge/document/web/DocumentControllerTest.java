package com.xjjk.knowledge.document.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.common.error.GlobalExceptionHandler;
import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.service.DocumentUploadService;
import com.xjjk.knowledge.document.service.DocumentQueryService;
import com.xjjk.knowledge.publication.PublicationAction;
import com.xjjk.knowledge.publication.PublicationRecord;
import com.xjjk.knowledge.publication.PublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentControllerTest {

    private DocumentUploadService service;
    private MockMvc mvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        service = mock(DocumentUploadService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        AdminPrincipal principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L,
                Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
        authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of());
    }

    @Test
    void uploadsMultipartFileAndReturnsDraftVersion() throws Exception {
        CreatedDocument created = createdDocument();
        when(service.uploadNewDocument(
                any(AdminPrincipal.class), eq(1L), eq(10L), eq("退款政策"),
                eq("refund.pdf"), eq("application/pdf"), any(byte[].class), eq("req-upload")))
                .thenReturn(created);
        MockMultipartFile file = new MockMultipartFile(
                "file", "refund.pdf", "application/pdf",
                "%PDF-1.7\ncontent".getBytes(StandardCharsets.US_ASCII));

        mvc.perform(multipart("/api/v1/admin/tenants/1/knowledge-bases/10/documents")
                        .file(file)
                        .param("title", "退款政策")
                        .principal(authentication)
                        .header("X-Request-Id", "req-upload"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-upload"))
                .andExpect(jsonPath("$.data.id").value(13))
                .andExpect(jsonPath("$.data.createdVersion.id").value(21))
                .andExpect(jsonPath("$.data.createdVersion.status").value("UPLOADED"));
    }

    @Test
    void uploadsNewVersionToExplicitDocument() throws Exception {
        CreatedDocument created = createdDocument();
        when(service.uploadNewVersion(
                any(AdminPrincipal.class), eq(1L), eq(10L), eq(13L),
                eq("refund-v2.pdf"), eq("application/pdf"), any(byte[].class), eq("req-v2")))
                .thenReturn(created);
        MockMultipartFile file = new MockMultipartFile(
                "file", "refund-v2.pdf", "application/pdf",
                "%PDF-1.7\nsecond".getBytes(StandardCharsets.US_ASCII));

        mvc.perform(multipart("/api/v1/admin/tenants/1/knowledge-bases/10/documents/13/versions")
                        .file(file)
                        .principal(authentication)
                        .header("X-Request-Id", "req-v2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-v2"))
                .andExpect(jsonPath("$.data.id").value(13))
                .andExpect(jsonPath("$.data.createdVersion.id").value(21));
    }

    @Test
    void publishesReadyVersionWithRequestId() throws Exception {
        PublicationService publications = mock(PublicationService.class);
        MockMvc publicationMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(service, null, null, publications))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        PublicationRecord record = new PublicationRecord(
                31L, 1L, 10L, 13L, null, 21L, PublicationAction.PUBLISH,
                10567L, "publish-1", 2, "manifest", LocalDateTime.of(2026, 9, 9, 21, 0));
        when(publications.publish(any(AdminPrincipal.class), eq(1L), eq(10L), eq(13L), eq(21L), eq("publish-1")))
                .thenReturn(record);

        publicationMvc.perform(post("/api/v1/admin/tenants/1/knowledge-bases/10/documents/13/versions/21/publish")
                        .principal(authentication).header("X-Request-Id", "publish-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "publish-1"))
                .andExpect(jsonPath("$.data.action").value("PUBLISH"))
                .andExpect(jsonPath("$.data.toVersionId").value(21));
    }

    @Test
    void listsDocumentsWithTheirVersionStatusAndChunkCount() throws Exception {
        DocumentQueryService queries = mock(DocumentQueryService.class);
        MockMvc queryMvc = MockMvcBuilders
                .standaloneSetup(new DocumentController(service, queries, null, null))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        CreatedDocument created = createdDocument();
        DocumentVersion uploaded = created.version();
        DocumentVersion ready = new DocumentVersion(
                uploaded.id(), uploaded.tenantId(), uploaded.knowledgeBaseId(), uploaded.documentId(),
                uploaded.versionNumber(), DocumentStatus.READY, uploaded.originalFilename(),
                uploaded.fileExtension(), uploaded.mimeType(), uploaded.fileSize(), uploaded.sourceSha256(),
                uploaded.sourceObjectKey(), uploaded.parsedObjectKey(), uploaded.parserVersion(), uploaded.chunkStrategyVersion(),
                "qwen3.7-text-embedding", 2560, "qwen37-customer-service-v2", "manifest",
                LocalDateTime.of(2026, 9, 10, 16, 24), false, 0, 34, 34,
                null, null, null, uploaded.createdBy(), uploaded.createdAt(), uploaded.updatedAt());
        when(queries.list(any(AdminPrincipal.class), eq(1L), eq(10L)))
                .thenReturn(List.of(created.document()));
        when(queries.versions(any(AdminPrincipal.class), eq(1L), eq(10L), eq(13L)))
                .thenReturn(List.of(ready));

        queryMvc.perform(get("/api/v1/admin/tenants/1/knowledge-bases/10/documents")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].versions[0].status").value("READY"))
                .andExpect(jsonPath("$.data[0].versions[0].versionNumber").value(1))
                .andExpect(jsonPath("$.data[0].versions[0].chunkCount").value(34));
    }

    private CreatedDocument createdDocument() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 9, 19, 0);
        KnowledgeDocument document = new KnowledgeDocument(
                13L, 1L, 10L, "退款政策", null, null,
                10567L, 10567L, 0, now, now);
        DocumentVersion version = new DocumentVersion(
                21L, 1L, 10L, 13L, 1, DocumentStatus.UPLOADED,
                "refund.pdf", "pdf", "application/pdf", 16L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "tenant/1/knowledge-base/10/document/13/version/21/source",
                null, null, null, null, null, null, null, null, false, 0, 0, 0,
                null, null, null, 10567L, now, now);
        return new CreatedDocument(document, version);
    }
}

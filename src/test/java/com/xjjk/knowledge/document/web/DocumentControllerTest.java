package com.xjjk.knowledge.document.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.common.error.GlobalExceptionHandler;
import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.service.DocumentUploadService;
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
        when(service.upload(
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
                .andExpect(jsonPath("$.data.currentDraftVersion.id").value(21))
                .andExpect(jsonPath("$.data.currentDraftVersion.status").value("UPLOADED"));
    }

    private CreatedDocument createdDocument() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 9, 19, 0);
        KnowledgeDocument document = new KnowledgeDocument(
                13L, 1L, 10L, "退款政策", 21L, null,
                10567L, 10567L, 1, now, now);
        DocumentVersion version = new DocumentVersion(
                21L, 1L, 10L, 13L, 1, DocumentStatus.UPLOADED,
                "refund.pdf", "pdf", "application/pdf", 16L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "tenant/1/knowledge-base/10/document/13/version/21/source",
                null, null, null, false, 0, 0, 0,
                null, null, null, 10567L, now, now);
        return new CreatedDocument(document, version);
    }
}

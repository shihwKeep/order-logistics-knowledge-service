package com.xjjk.knowledge.document.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.document.service.DocumentPreview;
import com.xjjk.knowledge.document.service.DocumentPreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentPreviewControllerTest {
    @Test
    void streamsAuthorizedFileWithNoStoreAndSandboxHeaders() throws Exception {
        DocumentPreviewService service = mock(DocumentPreviewService.class);
        AdminPrincipal principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
        when(service.source(principal, 1L, 2L, 3L, 4L)).thenReturn(new DocumentPreview(
                new ByteArrayInputStream("pdf".getBytes()), "退款 规则.pdf", "application/pdf", 3, true));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentPreviewController(service)).build();

        mvc.perform(get("/api/v1/admin/tenants/1/knowledge-bases/2/documents/3/versions/4/source")
                        .principal(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(content().bytes("pdf".getBytes()));
    }
}

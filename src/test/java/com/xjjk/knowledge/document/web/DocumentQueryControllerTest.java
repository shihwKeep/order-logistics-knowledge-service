package com.xjjk.knowledge.document.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.common.error.GlobalExceptionHandler;
import com.xjjk.knowledge.document.service.DocumentCorrectionService;
import com.xjjk.knowledge.document.service.DocumentQueryService;
import com.xjjk.knowledge.document.service.DocumentUnit;
import com.xjjk.knowledge.document.service.DocumentUploadService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentQueryControllerTest {
    private DocumentQueryService query;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        query = mock(DocumentQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(
                        mock(DocumentUploadService.class), query, mock(DocumentCorrectionService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        AdminPrincipal principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
        authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    @Test
    void returnsPagedLowConfidenceUnitsWithoutInternalObjectKey() throws Exception {
        when(query.units(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq(4L), org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(20)))
                .thenReturn(List.of(new DocumentUnit(
                        9L, 1L, 3L, 4L, "PAGE", 1, "第 1 页", "退款",
                        "模糊原文", "模糊原文", 0.51, true, 0)));

        mvc.perform(get("/api/v1/admin/tenants/1/knowledge-bases/2/documents/3/versions/4/units")
                        .param("lowConfidence", "true").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.data[0].locationLabel").value("第 1 页"))
                .andExpect(jsonPath("$.data[0].sourceObjectKey").doesNotExist());
    }
}

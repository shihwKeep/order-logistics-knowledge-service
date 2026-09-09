package com.xjjk.knowledge.audit;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditQueryControllerTest {
    @Test
    void returnsTenantScopedAuditPage() throws Exception {
        AuditQueryService service = mock(AuditQueryService.class);
        AdminPrincipal principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
        AuditLogEntry entry = new AuditLogEntry(
                9L, 1L, 10567L, 1L, "DOCUMENT_PUBLISH", "DOCUMENT_VERSION",
                "4", "req-1", "SUCCESS", "{\"versionId\":4}", LocalDateTime.now());
        when(service.list(principal, 1L, "DOCUMENT_PUBLISH", null, null, 0, 20))
                .thenReturn(new AuditPage(1, 0, 20, List.of(entry)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditQueryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/admin/tenants/1/audit-logs")
                        .param("action", "DOCUMENT_PUBLISH")
                        .principal(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].requestId").value("req-1"));
    }
}

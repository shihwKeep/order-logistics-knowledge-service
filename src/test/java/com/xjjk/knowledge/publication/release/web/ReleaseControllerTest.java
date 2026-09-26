package com.xjjk.knowledge.publication.release.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.common.error.GlobalExceptionHandler;
import com.xjjk.knowledge.publication.release.KnowledgeRelease;
import com.xjjk.knowledge.publication.release.ReleaseItem;
import com.xjjk.knowledge.publication.release.ReleaseService;
import com.xjjk.knowledge.publication.release.ReleaseStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReleaseControllerTest {
    private ReleaseService service;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        service = mock(ReleaseService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReleaseController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        AdminPrincipal principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L,
                Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
        authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of());
    }

    @Test
    void createsReleaseAsynchronously() throws Exception {
        when(service.create(any(), eq(1L), eq(7L), any(), eq("batch-1")))
                .thenReturn(release(4L, ReleaseStatus.PREPARING, 3L));

        mvc.perform(post("/api/v1/admin/tenants/1/knowledge-bases/7/releases")
                        .principal(authentication)
                        .header("X-Request-Id", "batch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replacements":[{"documentId":11,"versionId":91}]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Request-Id", "batch-1"))
                .andExpect(jsonPath("$.data.id").value(4))
                .andExpect(jsonPath("$.data.status").value("PREPARING"));
    }

    @Test
    void listsAndReadsReleaseManifest() throws Exception {
        KnowledgeRelease release = release(4L, ReleaseStatus.ACTIVE, 3L);
        when(service.list(any(), eq(1L), eq(7L))).thenReturn(List.of(release));
        when(service.get(any(), eq(1L), eq(7L), eq(4L))).thenReturn(release);
        when(service.items(any(), eq(1L), eq(7L), eq(4L))).thenReturn(List.of(
                new ReleaseItem(4L, 1L, 7L, 11L, 91L, "a".repeat(64))));

        mvc.perform(get("/api/v1/admin/tenants/1/knowledge-bases/7/releases")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].releaseNumber").value(4));

        mvc.perform(get("/api/v1/admin/tenants/1/knowledge-bases/7/releases/4")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].documentId").value(11))
                .andExpect(jsonPath("$.data.items[0].versionId").value(91));
    }

    @Test
    void rollsBackByCreatingAnotherRelease() throws Exception {
        when(service.rollback(any(), eq(1L), eq(7L), eq(4L), eq("rollback-4")))
                .thenReturn(release(5L, ReleaseStatus.PREPARING, 3L));

        mvc.perform(post("/api/v1/admin/tenants/1/knowledge-bases/7/releases/4/rollback")
                        .principal(authentication)
                        .header("X-Request-Id", "rollback-4"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PREPARING"))
                .andExpect(jsonPath("$.data.baseReleaseId").value(3));
    }

    private KnowledgeRelease release(long id, ReleaseStatus status, Long baseReleaseId) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 26, 20, 0);
        return new KnowledgeRelease(
                id, 1L, 7L, (int) id, status, baseReleaseId, "request-" + id,
                "a".repeat(64), 6, 10567L, null, now,
                status == ReleaseStatus.ACTIVE ? now : null, now);
    }
}

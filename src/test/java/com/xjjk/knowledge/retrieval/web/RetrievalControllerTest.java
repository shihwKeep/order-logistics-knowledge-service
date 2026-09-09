package com.xjjk.knowledge.retrieval.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.common.error.GlobalExceptionHandler;
import com.xjjk.knowledge.retrieval.model.DegradationMode;
import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import com.xjjk.knowledge.retrieval.model.RecallSource;
import com.xjjk.knowledge.retrieval.model.RetrievalResult;
import com.xjjk.knowledge.retrieval.service.HybridRetrievalService;
import com.xjjk.knowledge.tenant.TenantAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RetrievalControllerTest {

    @Test
    void internalEndpointVerifiesCallerAndReturnsStableEvidenceShape() throws Exception {
        HybridRetrievalService service = mock(HybridRetrievalService.class);
        InternalRequestVerifier verifier = mock(InternalRequestVerifier.class);
        doNothing().when(verifier).verify(
                eq(1L), eq(10567L), eq(1000L), eq("nonce"), eq("signature"), eq("怎么退款"), eq(List.of(2L)));
        when(service.retrieve(eq(1L), eq(10567L), any(), eq("怎么退款"), eq(List.of(2L))))
                .thenReturn(result());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalRetrievalController(service, verifier))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/api/v1/internal/knowledge/retrieve")
                        .contentType("application/json")
                        .header("X-Knowledge-Tenant-Id", "1")
                        .header("X-Knowledge-User-Id", "10567")
                        .header("X-Knowledge-Timestamp", "1000")
                        .header("X-Knowledge-Nonce", "nonce")
                        .header("X-Knowledge-Signature", "signature")
                        .header("X-Request-Id", "request-1")
                        .content("{\"question\":\"怎么退款\",\"knowledgeBaseIds\":[2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerable").value(true))
                .andExpect(jsonPath("$.data.evidences[0].documentTitle").value("退款规则"))
                .andExpect(jsonPath("$.data.evidences[0].content").value("签收后七日内可申请"));
    }

    @Test
    void adminCanInspectOwnDraftButCannotForgeAnotherTenant() throws Exception {
        HybridRetrievalService service = mock(HybridRetrievalService.class);
        when(service.retrieveAdmin(eq(1L), eq(10567L), any(), eq("问题"), eq(List.of()), eq(IndexLayer.DRAFT)))
                .thenReturn(result());
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new AdminRetrievalController(service, new TenantAccessGuard()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        AdminPrincipal principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L, Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());

        mvc.perform(post("/api/v1/admin/tenants/1/knowledge/retrieve?layer=DRAFT")
                        .principal(authentication).header("X-Request-Id", "admin-1")
                        .contentType("application/json").content("{\"question\":\"问题\",\"knowledgeBaseIds\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/tenants/2/knowledge/retrieve?layer=DRAFT")
                        .principal(authentication).header("X-Request-Id", "admin-2")
                        .contentType("application/json").content("{\"question\":\"问题\",\"knowledgeBaseIds\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
    }

    private RetrievalResult result() {
        IndexChunk chunk = new IndexChunk(
                "1-3-4-0", 1L, 2L, 3L, 4L, 0, "退款规则", "售后",
                "签收后七日内可申请", "hash", "{\"pageNumber\":3}");
        RankedEvidence evidence = new RankedEvidence(
                chunk, 0.9D, 0.03D, Set.of(RecallSource.KEYWORD, RecallSource.VECTOR), 1);
        return new RetrievalResult(true, List.of(evidence), "strategy-v1", DegradationMode.NONE,
                "OK", 1, 1, 1);
    }
}

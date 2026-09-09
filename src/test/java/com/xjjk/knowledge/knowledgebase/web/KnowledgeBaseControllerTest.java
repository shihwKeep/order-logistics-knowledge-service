package com.xjjk.knowledge.knowledgebase.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.common.error.GlobalExceptionHandler;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBase;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBaseStatus;
import com.xjjk.knowledge.knowledgebase.service.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBaseControllerTest {

    private KnowledgeBaseService service;
    private MockMvc mvc;
    private AdminPrincipal principal;
    private Authentication authentication;
    private KnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {
        service = mock(KnowledgeBaseService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new KnowledgeBaseController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L,
                Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
        authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of());
        OffsetDateTime now = OffsetDateTime.parse("2026-09-09T18:00:00+08:00");
        knowledgeBase = new KnowledgeBase(
                10L, 1L, "售后规则", "退款与换货政策",
                KnowledgeBaseStatus.ENABLED, 0, now, now);
    }

    @Test
    void listsAndGetsTenantKnowledgeBases() throws Exception {
        when(service.list(principal, 1L)).thenReturn(List.of(knowledgeBase));
        when(service.get(principal, 1L, 10L)).thenReturn(knowledgeBase);

        mvc.perform(get("/api/v1/admin/tenants/1/knowledge-bases")
                        .principal(authentication)
                        .header("X-Request-Id", "req-list"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-list"))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("售后规则"));

        mvc.perform(get("/api/v1/admin/tenants/1/knowledge-bases/10")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.status").value("ENABLED"));
    }

    @Test
    void createsAndUpdatesWithValidatedContractAndSameRequestId() throws Exception {
        when(service.create(
                principal, 1L, "售后规则", "退款与换货政策", "req-create"))
                .thenReturn(knowledgeBase);
        KnowledgeBase updated = new KnowledgeBase(
                10L, 1L, "售后规则新版", "新说明",
                KnowledgeBaseStatus.ENABLED, 1,
                knowledgeBase.createdAt(), knowledgeBase.updatedAt());
        when(service.update(
                principal, 1L, 10L, 0, "售后规则新版", "新说明", "req-update"))
                .thenReturn(updated);

        mvc.perform(post("/api/v1/admin/tenants/1/knowledge-bases")
                        .principal(authentication)
                        .header("X-Request-Id", "req-create")
                        .contentType("application/json")
                        .content("{\"name\":\"售后规则\",\"description\":\"退款与换货政策\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-create"))
                .andExpect(jsonPath("$.data.tenantId").value(1))
                .andExpect(jsonPath("$.data.rowVersion").value(0));

        mvc.perform(put("/api/v1/admin/tenants/1/knowledge-bases/10")
                        .principal(authentication)
                        .header("X-Request-Id", "req-update")
                        .contentType("application/json")
                        .content("""
                                {"name":"售后规则新版","description":"新说明","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVersion").value(1));
    }

    @Test
    void enablesDisablesAndDeletesThroughExplicitTenantRoute() throws Exception {
        when(service.setStatus(
                principal, 1L, 10L, KnowledgeBaseStatus.ENABLED, "req-enable"))
                .thenReturn(knowledgeBase);
        KnowledgeBase disabled = new KnowledgeBase(
                10L, 1L, "售后规则", "退款与换货政策",
                KnowledgeBaseStatus.DISABLED, 1,
                knowledgeBase.createdAt(), knowledgeBase.updatedAt());
        when(service.setStatus(
                principal, 1L, 10L, KnowledgeBaseStatus.DISABLED, "req-disable"))
                .thenReturn(disabled);

        mvc.perform(post("/api/v1/admin/tenants/1/knowledge-bases/10/enable")
                        .principal(authentication)
                        .header("X-Request-Id", "req-enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENABLED"));
        mvc.perform(post("/api/v1/admin/tenants/1/knowledge-bases/10/disable")
                        .principal(authentication)
                        .header("X-Request-Id", "req-disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        mvc.perform(delete("/api/v1/admin/tenants/1/knowledge-bases/10")
                        .principal(authentication)
                        .header("X-Request-Id", "req-delete"))
                .andExpect(status().isOk());

        verify(service).delete(principal, 1L, 10L, "req-delete");
    }

    @Test
    void rejectsBlankNameAndMapsCrossTenantDenial() throws Exception {
        mvc.perform(post("/api/v1/admin/tenants/1/knowledge-bases")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        when(service.list(principal, 2L))
                .thenThrow(new BusinessException(ApiErrorCode.TENANT_ACCESS_DENIED));
        mvc.perform(get("/api/v1/admin/tenants/2/knowledge-bases")
                        .principal(authentication))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
    }

    @Test
    void generatedRequestIdIsPassedToAuditServiceCall() throws Exception {
        when(service.create(
                org.mockito.ArgumentMatchers.eq(principal),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("售后规则"),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(knowledgeBase);
        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);

        String returned = mvc.perform(post("/api/v1/admin/tenants/1/knowledge-bases")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("{\"name\":\"售后规则\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn()
                .getResponse()
                .getHeader("X-Request-Id");

        verify(service).create(
                org.mockito.ArgumentMatchers.eq(principal),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("售后规则"),
                org.mockito.ArgumentMatchers.eq(null),
                requestId.capture());
        assertThat(requestId.getValue()).isEqualTo(returned).hasSizeLessThanOrEqualTo(64);
    }
}

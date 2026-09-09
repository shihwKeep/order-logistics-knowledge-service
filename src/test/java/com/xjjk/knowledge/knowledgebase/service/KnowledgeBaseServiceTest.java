package com.xjjk.knowledge.knowledgebase.service;

import com.xjjk.knowledge.audit.AuditAction;
import com.xjjk.knowledge.audit.AuditService;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBase;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBaseStatus;
import com.xjjk.knowledge.knowledgebase.persistence.KnowledgeBaseRepository;
import com.xjjk.knowledge.tenant.TenantAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceTest {

    private TenantAccessGuard guard;
    private KnowledgeBaseRepository repository;
    private AuditService auditService;
    private KnowledgeBaseService service;
    private AdminPrincipal principal;

    @BeforeEach
    void setUp() {
        guard = mock(TenantAccessGuard.class);
        repository = mock(KnowledgeBaseRepository.class);
        auditService = mock(AuditService.class);
        service = new KnowledgeBaseService(guard, repository, auditService);
        principal = new AdminPrincipal(
                10567L, "74680", "石海文", 1L,
                Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
    }

    @Test
    void authorizesThenCreatesAndAuditsAllowListedMetadata() {
        KnowledgeBase created = knowledgeBase(10L, KnowledgeBaseStatus.ENABLED, 0);
        when(repository.create(1L, 10567L, "售后规则", "退款与换货政策"))
                .thenReturn(created);

        KnowledgeBase result = service.create(
                principal, 1L, "售后规则", "退款与换货政策", "req-1");

        assertThat(result).isEqualTo(created);
        InOrder order = inOrder(guard, repository, auditService);
        order.verify(guard).requireManage(principal, 1L);
        order.verify(repository).create(1L, 10567L, "售后规则", "退款与换货政策");
        order.verify(auditService).success(
                eq(1L),
                eq(principal),
                eq(AuditAction.KNOWLEDGE_BASE_CREATE),
                eq("10"),
                eq("req-1"),
                eq(Map.of("knowledgeBaseName", "售后规则")));
    }

    @Test
    void crossTenantDenialStopsBeforeRepositoryAndSuccessAudit() {
        BusinessException denied = new BusinessException(ApiErrorCode.TENANT_ACCESS_DENIED);
        org.mockito.Mockito.doThrow(denied).when(guard).requireManage(principal, 2L);

        assertThatThrownBy(() -> service.create(
                principal, 2L, "越权知识库", "不应创建", "req-2"))
                .isSameAs(denied);

        verify(repository, never()).create(
                eq(2L), eq(10567L), anyString(), anyString());
        verify(auditService, never()).success(
                eq(2L), eq(principal), eq(AuditAction.KNOWLEDGE_BASE_CREATE),
                anyString(), anyString(), anyMap());
    }

    @Test
    void statusChangeAndDeleteWriteSpecificAuditFacts() {
        KnowledgeBase enabled = knowledgeBase(10L, KnowledgeBaseStatus.ENABLED, 0);
        KnowledgeBase disabled = knowledgeBase(10L, KnowledgeBaseStatus.DISABLED, 1);
        when(repository.findById(1L, 10L)).thenReturn(Optional.of(enabled));
        when(repository.setStatus(1L, 10L, KnowledgeBaseStatus.DISABLED, 10567L))
                .thenReturn(disabled);

        service.setStatus(
                principal, 1L, 10L, KnowledgeBaseStatus.DISABLED, "req-3");
        service.delete(principal, 1L, 10L, "req-4");

        verify(auditService).success(
                1L,
                principal,
                AuditAction.KNOWLEDGE_BASE_DISABLE,
                "10",
                "req-3",
                Map.of(
                        "knowledgeBaseName", "售后规则",
                        "previousStatus", "ENABLED",
                        "newStatus", "DISABLED"));
        verify(auditService).success(
                1L,
                principal,
                AuditAction.KNOWLEDGE_BASE_DELETE,
                "10",
                "req-4",
                Map.of("knowledgeBaseName", "售后规则"));
    }

    private static KnowledgeBase knowledgeBase(
            long id,
            KnowledgeBaseStatus status,
            int rowVersion) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-09T18:00:00+08:00");
        return new KnowledgeBase(
                id, 1L, "售后规则", "退款与换货政策",
                status, rowVersion, now, now);
    }
}

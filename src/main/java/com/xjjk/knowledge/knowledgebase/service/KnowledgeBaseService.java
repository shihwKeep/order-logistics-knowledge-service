package com.xjjk.knowledge.knowledgebase.service;

import com.xjjk.knowledge.audit.AuditAction;
import com.xjjk.knowledge.audit.AuditService;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBase;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBaseStatus;
import com.xjjk.knowledge.knowledgebase.persistence.KnowledgeBaseRepository;
import com.xjjk.knowledge.tenant.TenantAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 知识库管理应用服务：所有数据库访问前必须先做租户授权。 */
@Service
public class KnowledgeBaseService {

    private final TenantAccessGuard accessGuard;
    private final KnowledgeBaseRepository repository;
    private final AuditService auditService;

    public KnowledgeBaseService(
            TenantAccessGuard accessGuard,
            KnowledgeBaseRepository repository,
            AuditService auditService) {
        this.accessGuard = accessGuard;
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public KnowledgeBase create(
            AdminPrincipal principal,
            long tenantId,
            String name,
            String description,
            String requestId) {
        accessGuard.requireManage(principal, tenantId);
        validateName(name);
        KnowledgeBase created = repository.create(
                tenantId, principal.userId(), name.trim(), normalizeDescription(description));
        auditService.success(
                tenantId,
                principal,
                AuditAction.KNOWLEDGE_BASE_CREATE,
                Long.toString(created.id()),
                requestId,
                Map.of("knowledgeBaseName", created.name()));
        return created;
    }

    public KnowledgeBase get(AdminPrincipal principal, long tenantId, long knowledgeBaseId) {
        accessGuard.requireManage(principal, tenantId);
        return requireExisting(tenantId, knowledgeBaseId);
    }

    public List<KnowledgeBase> list(AdminPrincipal principal, long tenantId) {
        accessGuard.requireManage(principal, tenantId);
        return repository.list(tenantId);
    }

    @Transactional
    public KnowledgeBase update(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            int expectedVersion,
            String name,
            String description,
            String requestId) {
        accessGuard.requireManage(principal, tenantId);
        validateName(name);
        KnowledgeBase updated = repository.update(
                tenantId,
                knowledgeBaseId,
                expectedVersion,
                name.trim(),
                normalizeDescription(description),
                principal.userId());
        auditService.success(
                tenantId,
                principal,
                AuditAction.KNOWLEDGE_BASE_UPDATE,
                Long.toString(updated.id()),
                requestId,
                Map.of("knowledgeBaseName", updated.name()));
        return updated;
    }

    @Transactional
    public KnowledgeBase setStatus(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            KnowledgeBaseStatus status,
            String requestId) {
        accessGuard.requireManage(principal, tenantId);
        KnowledgeBase previous = requireExisting(tenantId, knowledgeBaseId);
        KnowledgeBase updated = repository.setStatus(
                tenantId, knowledgeBaseId, status, principal.userId());
        AuditAction action = status == KnowledgeBaseStatus.ENABLED
                ? AuditAction.KNOWLEDGE_BASE_ENABLE
                : AuditAction.KNOWLEDGE_BASE_DISABLE;
        auditService.success(
                tenantId,
                principal,
                action,
                Long.toString(updated.id()),
                requestId,
                Map.of(
                        "knowledgeBaseName", updated.name(),
                        "previousStatus", previous.status().name(),
                        "newStatus", updated.status().name()));
        return updated;
    }

    @Transactional
    public void delete(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            String requestId) {
        accessGuard.requireManage(principal, tenantId);
        KnowledgeBase existing = requireExisting(tenantId, knowledgeBaseId);
        repository.softDelete(tenantId, knowledgeBaseId, principal.userId());
        auditService.success(
                tenantId,
                principal,
                AuditAction.KNOWLEDGE_BASE_DELETE,
                Long.toString(knowledgeBaseId),
                requestId,
                Map.of("knowledgeBaseName", existing.name()));
    }

    private KnowledgeBase requireExisting(long tenantId, long knowledgeBaseId) {
        return repository.findById(tenantId, knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String normalized = description.trim();
        if (normalized.length() > 500) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }
}

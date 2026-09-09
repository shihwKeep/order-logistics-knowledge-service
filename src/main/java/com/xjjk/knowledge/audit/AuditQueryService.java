package com.xjjk.knowledge.audit;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.tenant.TenantAccessGuard;
import org.springframework.stereotype.Service;

@Service
public class AuditQueryService {
    private final TenantAccessGuard tenants;
    private final AuditQueryRepository repository;

    public AuditQueryService(TenantAccessGuard tenants, AuditQueryRepository repository) {
        this.tenants = tenants;
        this.repository = repository;
    }

    public AuditPage list(
            AdminPrincipal principal, long tenantId, String action, String resourceType, String requestId,
            int offset, int limit) {
        tenants.requireManage(principal, tenantId);
        String safeAction = normalize(action, 60);
        String safeResourceType = normalize(resourceType, 40);
        String safeRequestId = normalize(requestId, 64);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        long total = repository.count(tenantId, safeAction, safeResourceType, safeRequestId);
        return new AuditPage(
                total, safeOffset, safeLimit,
                repository.list(
                        tenantId, safeAction, safeResourceType, safeRequestId, safeOffset, safeLimit));
    }

    private String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}

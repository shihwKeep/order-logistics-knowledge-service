package com.xjjk.knowledge.audit;

import java.time.LocalDateTime;

/** 管理台可查询的脱敏审计记录。detailJson 仅包含 AuditService 白名单字段。 */
public record AuditLogEntry(
        long id,
        long tenantId,
        long actorUserId,
        long actorTenantId,
        String action,
        String resourceType,
        String resourceId,
        String requestId,
        String outcome,
        String detailJson,
        LocalDateTime createdAt) {
}

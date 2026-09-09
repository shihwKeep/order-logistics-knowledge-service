package com.xjjk.knowledge.audit;

/** 审计落库事件；detailJson 只能由白名单字段生成。 */
public record AuditEvent(
        long tenantId,
        long actorUserId,
        long actorTenantId,
        String action,
        String resourceType,
        String resourceId,
        String requestId,
        String outcome,
        String detailJson
) {
}

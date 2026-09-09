package com.xjjk.knowledge.audit;

import java.util.List;

public interface AuditQueryRepository {
    long count(long tenantId, String action, String resourceType, String requestId);

    List<AuditLogEntry> list(
            long tenantId, String action, String resourceType, String requestId, int offset, int limit);
}

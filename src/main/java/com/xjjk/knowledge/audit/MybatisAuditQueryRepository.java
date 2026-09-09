package com.xjjk.knowledge.audit;

import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisAuditQueryRepository implements AuditQueryRepository {
    private final AuditQueryMapper mapper;

    public MybatisAuditQueryRepository(AuditQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long count(long tenantId, String action, String resourceType, String requestId) {
        return mapper.count(tenantId, action, resourceType, requestId);
    }

    @Override
    public List<AuditLogEntry> list(
            long tenantId, String action, String resourceType, String requestId, int offset, int limit) {
        return mapper.list(tenantId, action, resourceType, requestId, offset, limit);
    }
}

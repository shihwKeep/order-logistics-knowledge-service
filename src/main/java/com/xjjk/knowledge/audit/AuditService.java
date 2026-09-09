package com.xjjk.knowledge.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 将管理动作写入审计表，并严格过滤可记录的业务元数据。 */
@Service
public class AuditService {

    private static final Set<String> ALLOWED_DETAIL_KEYS = Set.of(
            "knowledgeBaseName", "previousStatus", "newStatus",
            "documentTitle", "versionId");

    private final AuditMapper mapper;
    private final ObjectMapper objectMapper;

    public AuditService(AuditMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void success(
            long tenantId,
            AdminPrincipal actor,
            AuditAction action,
            String resourceId,
            String requestId,
            Map<String, ?> details) {
        success(tenantId, actor, action, "KNOWLEDGE_BASE", resourceId, requestId, details);
    }

    public void success(
            long tenantId,
            AdminPrincipal actor,
            AuditAction action,
            String resourceType,
            String resourceId,
            String requestId,
            Map<String, ?> details) {
        Map<String, Object> safeDetails = new LinkedHashMap<>();
        if (details != null) {
            details.forEach((key, value) -> {
                if (ALLOWED_DETAIL_KEYS.contains(key)) {
                    safeDetails.put(key, value);
                }
            });
        }

        try {
            mapper.insert(new AuditEvent(
                    tenantId,
                    actor.userId(),
                    actor.tenantId(),
                    action.name(),
                    resourceType,
                    resourceId,
                    requestId,
                    "SUCCESS",
                    objectMapper.writeValueAsString(safeDetails)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize audit metadata", exception);
        }
    }
}

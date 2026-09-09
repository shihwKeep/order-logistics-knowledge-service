package com.xjjk.knowledge.audit;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 知识库管理审计写入。 */
@Mapper
public interface AuditMapper {

    @Insert("""
            INSERT INTO kb_audit_log
              (tenant_id, actor_user_id, actor_tenant_id, action, resource_type,
               resource_id, request_id, outcome, detail_json)
            VALUES
              (#{tenantId}, #{actorUserId}, #{actorTenantId}, #{action}, #{resourceType},
               #{resourceId}, #{requestId}, #{outcome}, #{detailJson})
            """)
    int insert(AuditEvent event);
}

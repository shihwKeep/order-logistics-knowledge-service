package com.xjjk.knowledge.audit;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AuditQueryMapper {
    String FILTER = """
            <where>
              tenant_id=#{tenantId}
              <if test="action != null">AND action=#{action}</if>
              <if test="resourceType != null">AND resource_type=#{resourceType}</if>
              <if test="requestId != null">AND request_id=#{requestId}</if>
            </where>
            """;

    @Select("<script>SELECT COUNT(*) FROM kb_audit_log " + FILTER + "</script>")
    long count(
            @Param("tenantId") long tenantId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("requestId") String requestId);

    @Select("""
            <script>
            SELECT id,tenant_id,actor_user_id,actor_tenant_id,action,resource_type,resource_id,
                   request_id,outcome,CAST(detail_json AS CHAR) AS detail_json,created_at
              FROM kb_audit_log
            """ + FILTER + """
             ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuditLogEntry> list(
            @Param("tenantId") long tenantId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("requestId") String requestId,
            @Param("offset") int offset,
            @Param("limit") int limit);
}

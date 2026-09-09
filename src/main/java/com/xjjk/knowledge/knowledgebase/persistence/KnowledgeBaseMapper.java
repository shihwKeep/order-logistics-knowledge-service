package com.xjjk.knowledge.knowledgebase.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 所有 SQL 都显式携带 tenant_id 与 is_deleted 条件，避免跨租户误读写。 */
@Mapper
public interface KnowledgeBaseMapper {

    @Insert("""
            INSERT INTO kb_knowledge_base
              (tenant_id, name, description, status, created_by, updated_by)
            VALUES
              (#{tenantId}, #{name}, #{description}, #{status}, #{createdBy}, #{updatedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeBaseEntity entity);

    @Select("""
            SELECT id, tenant_id, name, description, status, created_by, updated_by,
                   row_version, is_deleted AS deleted, created_at, updated_at
              FROM kb_knowledge_base
             WHERE tenant_id = #{tenantId}
               AND id = #{knowledgeBaseId}
               AND is_deleted = 0
            """)
    KnowledgeBaseEntity findById(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId);

    @Select("""
            SELECT id, tenant_id, name, description, status, created_by, updated_by,
                   row_version, is_deleted AS deleted, created_at, updated_at
              FROM kb_knowledge_base
             WHERE tenant_id = #{tenantId}
               AND is_deleted = 0
             ORDER BY id DESC
            """)
    List<KnowledgeBaseEntity> list(@Param("tenantId") long tenantId);

    @Update("""
            UPDATE kb_knowledge_base
               SET name = #{name},
                   description = #{description},
                   updated_by = #{actorUserId},
                   row_version = row_version + 1
             WHERE tenant_id = #{tenantId}
               AND id = #{knowledgeBaseId}
               AND row_version = #{expectedVersion}
               AND is_deleted = 0
            """)
    int update(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("expectedVersion") int expectedVersion,
            @Param("name") String name,
            @Param("description") String description,
            @Param("actorUserId") long actorUserId);

    @Update("""
            UPDATE kb_knowledge_base
               SET status = #{status},
                   updated_by = #{actorUserId},
                   row_version = row_version + 1
             WHERE tenant_id = #{tenantId}
               AND id = #{knowledgeBaseId}
               AND row_version = #{expectedVersion}
               AND is_deleted = 0
            """)
    int setStatus(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("expectedVersion") int expectedVersion,
            @Param("status") String status,
            @Param("actorUserId") long actorUserId);

    @Update("""
            UPDATE kb_knowledge_base
               SET is_deleted = 1,
                   updated_by = #{actorUserId},
                   row_version = row_version + 1
             WHERE tenant_id = #{tenantId}
               AND id = #{knowledgeBaseId}
               AND row_version = #{expectedVersion}
               AND is_deleted = 0
            """)
    int softDelete(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("expectedVersion") int expectedVersion,
            @Param("actorUserId") long actorUserId);
}

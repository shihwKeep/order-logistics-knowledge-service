package com.xjjk.knowledge.retrieval.service;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DraftVersionMapper {
    @Select("""
            <script>
            SELECT CONCAT(d.id, ':', d.current_draft_version_id)
              FROM kb_document d
              JOIN kb_knowledge_base kb ON kb.tenant_id=d.tenant_id AND kb.id=d.knowledge_base_id
             WHERE d.tenant_id=#{tenantId} AND d.is_deleted=0 AND kb.is_deleted=0
               AND d.current_draft_version_id IS NOT NULL
               AND (
                 <foreach collection="references" item="reference" separator=" OR ">
                   (d.id=#{reference.documentId} AND d.current_draft_version_id=#{reference.versionId})
                 </foreach>
               )
            </script>
            """)
    List<String> findDraftKeys(
            @Param("tenantId") long tenantId,
            @Param("references") List<VersionReference> references);
}

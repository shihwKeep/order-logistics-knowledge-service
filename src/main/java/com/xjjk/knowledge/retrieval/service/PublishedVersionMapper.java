package com.xjjk.knowledge.retrieval.service;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PublishedVersionMapper {
    @Select("""
            <script>
            SELECT CONCAT(item.document_id, ':', item.version_id)
              FROM kb_knowledge_base kb
              JOIN kb_release active_release
                ON active_release.id=kb.current_release_id
               AND active_release.tenant_id=kb.tenant_id
               AND active_release.knowledge_base_id=kb.id
               AND active_release.status='ACTIVE'
              JOIN kb_release_item item
                ON item.release_id=active_release.id
               AND item.tenant_id=kb.tenant_id
               AND item.knowledge_base_id=kb.id
             WHERE kb.tenant_id=#{tenantId}
               AND kb.is_deleted=0 AND kb.status='ENABLED'
               AND (
                 <foreach collection="references" item="reference" separator=" OR ">
                   (item.document_id=#{reference.documentId} AND item.version_id=#{reference.versionId})
                 </foreach>
               )
            </script>
            """)
    List<String> findPublishedKeys(
            @Param("tenantId") long tenantId,
            @Param("references") List<VersionReference> references);
}

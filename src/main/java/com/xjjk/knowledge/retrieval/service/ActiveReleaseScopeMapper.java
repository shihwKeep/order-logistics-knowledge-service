package com.xjjk.knowledge.retrieval.service;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ActiveReleaseScopeMapper {
    @Select("""
            <script>
            SELECT kb.id AS knowledge_base_id,
                   active_release.id AS release_id,
                   item.document_id,
                   item.version_id
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
               AND kb.is_deleted=0
               AND kb.status='ENABLED'
             <if test="knowledgeBaseIds != null and !knowledgeBaseIds.isEmpty()">
               AND kb.id IN
               <foreach collection="knowledgeBaseIds" item="knowledgeBaseId" open="(" separator="," close=")">
                 #{knowledgeBaseId}
               </foreach>
             </if>
             ORDER BY kb.id,item.document_id,item.version_id
            </script>
            """)
    List<ScopeRow> findActiveScope(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseIds") List<Long> knowledgeBaseIds);

    record ScopeRow(long knowledgeBaseId, long releaseId, long documentId, long versionId) {
    }
}

package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.persistence.DocumentVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface IndexRecoveryMapper {

    @Select("""
            SELECT v.*
              FROM kb_document d
              JOIN kb_document_version v
                ON v.tenant_id=d.tenant_id AND v.document_id=d.id
               AND v.id=d.current_published_version_id
             WHERE d.is_deleted=0 AND d.current_published_version_id IS NOT NULL
               AND v.index_manifest_sha256 IS NOT NULL
             ORDER BY v.tenant_id,v.document_id,v.id
            """)
    List<DocumentVersionEntity> listCurrentPublishedVersions();

    @Select("""
            SELECT v.*
              FROM kb_document d
              JOIN kb_document_version v
                ON v.tenant_id=d.tenant_id AND v.document_id=d.id
               AND v.id=d.current_draft_version_id
             WHERE d.is_deleted=0 AND d.current_draft_version_id IS NOT NULL
               AND v.status IN ('READY','PUBLISHED','ARCHIVED')
               AND v.index_manifest_sha256 IS NOT NULL
             ORDER BY v.tenant_id,v.document_id,v.id
            """)
    List<DocumentVersionEntity> listCurrentDraftVersions();
}

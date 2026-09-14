package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.persistence.DocumentVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Update("""
            UPDATE kb_document_version
               SET embedding_model=#{newModel},
                   embedding_dimension=#{newDimension},
                   embedding_instruction_version=#{newInstructionVersion},
                   updated_at=CURRENT_TIMESTAMP(3)
             WHERE id=#{id} AND tenant_id=#{tenantId} AND document_id=#{documentId}
               AND index_manifest_sha256=#{manifest}
               AND embedding_model <=> #{oldModel}
               AND embedding_dimension <=> #{oldDimension}
               AND embedding_instruction_version <=> #{oldInstructionVersion}
            """)
    int upgradeEmbeddingContract(
            @Param("id") long id,
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("manifest") String manifest,
            @Param("oldModel") String oldModel,
            @Param("oldDimension") Integer oldDimension,
            @Param("oldInstructionVersion") String oldInstructionVersion,
            @Param("newModel") String newModel,
            @Param("newDimension") int newDimension,
            @Param("newInstructionVersion") String newInstructionVersion);
}

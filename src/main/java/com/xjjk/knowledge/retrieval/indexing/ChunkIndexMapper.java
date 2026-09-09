package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.retrieval.model.IndexChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChunkIndexMapper {
    @Select("""
            SELECT CONCAT(c.tenant_id,'-',c.document_id,'-',c.version_id,'-',c.chunk_index) AS chunk_id,
                   c.tenant_id,c.knowledge_base_id,c.document_id,c.version_id,c.chunk_index,
                   d.title AS document_title,c.title_path,c.content,c.content_sha256,
                   CAST(c.location_json AS CHAR) AS location_json
              FROM kb_chunk c
              JOIN kb_document d ON d.tenant_id=c.tenant_id AND d.id=c.document_id AND d.is_deleted=0
             WHERE c.tenant_id=#{tenantId} AND c.document_id=#{documentId} AND c.version_id=#{versionId}
             ORDER BY c.chunk_index
            """)
    List<IndexChunk> listVersionChunks(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId);

    @Update("""
            UPDATE kb_document_version
               SET status='READY',embedding_model=#{model},embedding_dimension=#{dimension},
                   embedding_instruction_version=#{instructionVersion},
                   index_manifest_sha256=#{manifestSha256},indexed_at=CURRENT_TIMESTAMP(3),
                   failure_stage=NULL,last_error_code=NULL,last_error_message=NULL
             WHERE tenant_id=#{tenantId} AND document_id=#{documentId} AND id=#{versionId}
               AND status='INDEXING' AND chunk_count=#{chunkCount}
               AND correction_revision=#{correctionRevision}
               AND (#{taskId} IS NULL OR EXISTS (
                    SELECT 1 FROM kb_ingestion_task t
                     WHERE t.id=#{taskId} AND t.status='PROCESSING'
                       AND t.lease_token=#{leaseToken}
                       AND t.locked_until>=CURRENT_TIMESTAMP(3)
               ))
            """)
    int markReady(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("chunkCount") int chunkCount,
            @Param("correctionRevision") int correctionRevision,
            @Param("model") String model,
            @Param("dimension") int dimension,
            @Param("instructionVersion") String instructionVersion,
            @Param("manifestSha256") String manifestSha256,
            @Param("taskId") Long taskId,
            @Param("leaseToken") String leaseToken);
}

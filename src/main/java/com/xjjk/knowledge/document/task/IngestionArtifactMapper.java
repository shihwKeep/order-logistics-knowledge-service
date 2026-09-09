package com.xjjk.knowledge.document.task;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IngestionArtifactMapper {
    @Delete("DELETE FROM kb_chunk WHERE tenant_id=#{tenantId} AND version_id=#{versionId}")
    int deleteChunks(@Param("tenantId") long tenantId, @Param("versionId") long versionId);

    @Delete("DELETE FROM kb_document_unit WHERE tenant_id=#{tenantId} AND version_id=#{versionId}")
    int deleteUnits(@Param("tenantId") long tenantId, @Param("versionId") long versionId);

    @Insert("""
            INSERT INTO kb_document_unit
              (tenant_id,document_id,version_id,unit_type,unit_index,location_label,title_path,
               raw_text,effective_text,ocr_confidence,low_confidence,content_sha256)
            VALUES
              (#{tenantId},#{documentId},#{versionId},#{unitType},#{unitIndex},#{locationLabel},#{titlePath},
               #{rawText},#{effectiveText},#{ocrConfidence},#{lowConfidence},#{contentSha256})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUnit(DocumentUnitEntity entity);

    @Insert("""
            INSERT INTO kb_chunk
              (tenant_id,knowledge_base_id,document_id,version_id,unit_id,chunk_index,title_path,
               content,token_count,content_sha256,location_json)
            VALUES
              (#{tenantId},#{knowledgeBaseId},#{documentId},#{versionId},#{unitId},#{chunkIndex},#{titlePath},
               #{content},#{tokenCount},#{contentSha256},CAST(#{locationJson} AS JSON))
            """)
    int insertChunk(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("unitId") long unitId,
            @Param("chunkIndex") int chunkIndex,
            @Param("titlePath") String titlePath,
            @Param("content") String content,
            @Param("tokenCount") int tokenCount,
            @Param("contentSha256") String contentSha256,
            @Param("locationJson") String locationJson);

    @Update("""
            UPDATE kb_document_version
               SET status='INDEXING', parser_version=#{parserVersion}, chunk_strategy_version='structural-v1',
                   ocr_required=#{ocrRequired}, unit_count=#{unitCount}, chunk_count=#{chunkCount},
                   failure_stage=NULL,last_error_code=NULL,last_error_message=NULL
             WHERE tenant_id=#{tenantId} AND document_id=#{documentId} AND id=#{versionId}
            """)
    int markParsed(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("parserVersion") String parserVersion,
            @Param("ocrRequired") boolean ocrRequired,
            @Param("unitCount") int unitCount,
            @Param("chunkCount") int chunkCount);
}

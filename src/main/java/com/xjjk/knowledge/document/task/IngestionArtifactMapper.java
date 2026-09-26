package com.xjjk.knowledge.document.task;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IngestionArtifactMapper {
    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM kb_document_version
                 WHERE source_object_key=#{objectKey})
            """)
    boolean isSourceObjectReferenced(@Param("objectKey") String objectKey);

    @Insert("""
            INSERT IGNORE INTO kb_ingestion_task
              (tenant_id,knowledge_base_id,document_id,version_id,task_key,stage,status)
            VALUES
              (#{tenantId},#{knowledgeBaseId},#{documentId},#{versionId},#{taskKey},'INDEX','PENDING')
            """)
    int insertIndexTask(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("taskKey") String taskKey);

    @Insert("""
            INSERT INTO kb_outbox_event
              (event_id,tenant_id,aggregate_type,aggregate_id,event_type,payload_json)
            VALUES
              (#{eventId},#{tenantId},'DOCUMENT_VERSION',#{aggregateId},
               'DOCUMENT_INGESTION_REQUESTED',CAST(#{payloadJson} AS JSON))
            """)
    int insertIndexOutbox(
            @Param("eventId") String eventId,
            @Param("tenantId") long tenantId,
            @Param("aggregateId") String aggregateId,
            @Param("payloadJson") String payloadJson);

    @Select("""
            SELECT id,tenant_id,document_id,version_id,unit_type,unit_index,location_label,title_path,
                   raw_text,effective_text,ocr_confidence,low_confidence,content_sha256
              FROM kb_document_unit
             WHERE tenant_id=#{tenantId} AND document_id=#{documentId} AND version_id=#{versionId}
             ORDER BY unit_index
            """)
    java.util.List<DocumentUnitEntity> listUnits(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId);

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

    @Update("""
            UPDATE kb_document_version SET status='INDEXING',chunk_strategy_version='structural-v1',
                   chunk_count=#{chunkCount},failure_stage=NULL,last_error_code=NULL,last_error_message=NULL
             WHERE tenant_id=#{tenantId} AND document_id=#{documentId} AND id=#{versionId}
            """)
    int markRechunked(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("chunkCount") int chunkCount);

    @Update("""
            UPDATE kb_document_version v
               SET v.status='INDEXING',v.failure_stage=NULL,
                   v.last_error_code=NULL,v.last_error_message=NULL
             WHERE v.tenant_id=#{tenantId} AND v.document_id=#{documentId} AND v.id=#{versionId}
               AND v.correction_revision=#{correctionRevision}
               AND (v.status='INDEXING' OR (v.status='FAILED' AND v.failure_stage='INDEX'))
               AND EXISTS (
                    SELECT 1 FROM kb_ingestion_task t
                     WHERE t.id=#{taskId} AND t.status='PROCESSING'
                       AND t.lease_token=#{leaseToken}
                       AND t.locked_until>=CURRENT_TIMESTAMP(3)
               )
            """)
    int prepareIndexAttempt(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("correctionRevision") int correctionRevision,
            @Param("taskId") long taskId,
            @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE kb_document_version v
               SET v.status='FAILED',v.failure_stage=#{stage},v.last_error_code=#{errorCode}
             WHERE v.tenant_id=#{tenantId} AND v.document_id=#{documentId} AND v.id=#{versionId}
               AND v.correction_revision=#{correctionRevision}
               AND v.status NOT IN ('READY','PUBLISHED','ARCHIVED')
               AND EXISTS (
                    SELECT 1 FROM kb_ingestion_task t
                     WHERE t.id=#{taskId} AND t.status='PROCESSING'
                       AND t.lease_token=#{leaseToken}
                       AND t.locked_until>=CURRENT_TIMESTAMP(3)
               )
            """)
    int markFailedIfOwned(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("correctionRevision") int correctionRevision,
            @Param("taskId") long taskId,
            @Param("leaseToken") String leaseToken,
            @Param("stage") String stage,
            @Param("errorCode") String errorCode);

    @Insert("""
            INSERT IGNORE INTO kb_derived_index_cleanup
              (tenant_id,knowledge_base_id,document_id,version_id,index_layer,cleanup_reason,status)
            SELECT v.tenant_id,v.knowledge_base_id,v.document_id,v.id,
                   'DRAFT','INGESTION_FAILED','PENDING'
              FROM kb_document_version v
              JOIN kb_ingestion_task t
                ON t.tenant_id=v.tenant_id AND t.version_id=v.id
             WHERE t.id=#{taskId} AND t.status='DEAD'
               AND v.tenant_id=#{tenantId} AND v.document_id=#{documentId} AND v.id=#{versionId}
               AND v.status='FAILED'
            """)
    int insertFinalFailureCleanup(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("taskId") long taskId);
}

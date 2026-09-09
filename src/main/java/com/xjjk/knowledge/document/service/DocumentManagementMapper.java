package com.xjjk.knowledge.document.service;

import com.xjjk.knowledge.document.task.IngestionTask;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocumentManagementMapper {
    @Select("""
            <script>
            SELECT u.id,u.tenant_id,u.document_id,u.version_id,u.unit_type,u.unit_index,
                   u.location_label,u.title_path,u.raw_text,u.effective_text,u.ocr_confidence,
                   u.low_confidence,v.correction_revision
              FROM kb_document_unit u
              JOIN kb_document_version v ON v.tenant_id=u.tenant_id AND v.id=u.version_id
             WHERE u.tenant_id=#{tenantId} AND u.document_id=#{documentId} AND u.version_id=#{versionId}
             <if test="lowConfidence != null">AND u.low_confidence=#{lowConfidence}</if>
             ORDER BY u.unit_index LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DocumentUnitRow> listUnits(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("lowConfidence") Boolean lowConfidence,
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Select("""
            SELECT u.id,u.tenant_id,u.document_id,u.version_id,u.unit_type,u.unit_index,
                   u.location_label,u.title_path,u.raw_text,u.effective_text,u.ocr_confidence,
                   u.low_confidence,v.correction_revision
              FROM kb_document_unit u
              JOIN kb_document_version v ON v.tenant_id=u.tenant_id AND v.id=u.version_id
             WHERE u.tenant_id=#{tenantId} AND v.knowledge_base_id=#{knowledgeBaseId}
               AND u.document_id=#{documentId} AND u.version_id=#{versionId} AND u.id=#{unitId}
            """)
    DocumentUnitRow findUnit(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("unitId") long unitId);

    @Select("""
            SELECT correction_revision FROM kb_document_version
             WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId}
               AND document_id=#{documentId} AND id=#{versionId} FOR UPDATE
            """)
    Integer lockCorrectionRevision(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId);

    @Insert("""
            INSERT INTO kb_document_unit_revision
              (tenant_id,document_id,version_id,unit_id,revision_number,previous_text,corrected_text,corrected_by,request_id)
            VALUES
              (#{tenantId},#{documentId},#{versionId},#{unitId},#{revision},#{previousText},#{correctedText},#{actorId},#{requestId})
            """)
    int insertRevision(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("unitId") long unitId,
            @Param("revision") int revision,
            @Param("previousText") String previousText,
            @Param("correctedText") String correctedText,
            @Param("actorId") long actorId,
            @Param("requestId") String requestId);

    @Update("""
            UPDATE kb_document_unit SET effective_text=#{correctedText},content_sha256=#{sha256},low_confidence=0
             WHERE tenant_id=#{tenantId} AND document_id=#{documentId} AND version_id=#{versionId} AND id=#{unitId}
            """)
    int updateEffectiveText(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("unitId") long unitId,
            @Param("correctedText") String correctedText,
            @Param("sha256") String sha256);

    @Delete("DELETE FROM kb_chunk WHERE tenant_id=#{tenantId} AND version_id=#{versionId}")
    int deleteChunks(@Param("tenantId") long tenantId, @Param("versionId") long versionId);

    @Update("""
            UPDATE kb_document_version SET correction_revision=#{revision},status='CHUNKING',chunk_count=0
             WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId}
               AND document_id=#{documentId} AND id=#{versionId}
            """)
    int markCorrection(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("revision") int revision);

    @Insert("""
            INSERT INTO kb_ingestion_task
              (tenant_id,knowledge_base_id,document_id,version_id,task_key,stage,status)
            VALUES
              (#{tenantId},#{knowledgeBaseId},#{documentId},#{versionId},#{taskKey},'CHUNK','PENDING')
            """)
    int insertCorrectionTask(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("taskKey") String taskKey);

    @Insert("""
            INSERT INTO kb_outbox_event(event_id,tenant_id,aggregate_type,aggregate_id,event_type,payload_json)
            VALUES(#{eventId},#{tenantId},'DOCUMENT_VERSION',#{aggregateId},
                   'DOCUMENT_INGESTION_REQUESTED',CAST(#{payload} AS JSON))
            """)
    int insertOutbox(
            @Param("eventId") String eventId,
            @Param("tenantId") long tenantId,
            @Param("aggregateId") String aggregateId,
            @Param("payload") String payload);

    @Select("""
            SELECT id,tenant_id,knowledge_base_id,document_id,version_id,stage,status,retry_count,
                   next_run_at,lease_token,locked_by,locked_until,last_error_code
              FROM kb_ingestion_task
             WHERE tenant_id=#{tenantId} AND version_id=#{versionId} ORDER BY id DESC LIMIT 1
            """)
    IngestionTask findLatestTask(@Param("tenantId") long tenantId, @Param("versionId") long versionId);

    @Update("""
            UPDATE kb_ingestion_task SET status='PENDING',retry_count=0,next_run_at=CURRENT_TIMESTAMP(3),
                   lease_token=NULL,locked_by=NULL,locked_until=NULL,last_error_code=NULL,last_error_message=NULL
             WHERE id=#{taskId} AND tenant_id=#{tenantId} AND status IN ('RETRY','DEAD')
            """)
    int retryTask(@Param("tenantId") long tenantId, @Param("taskId") long taskId);
}

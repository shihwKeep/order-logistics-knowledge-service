package com.xjjk.knowledge.document.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 文档 SQL 必须同时约束 tenant_id 和资源归属，禁止只按全局主键读取。 */
@Mapper
public interface DocumentMapper {

    @Insert("""
            INSERT INTO kb_document
              (tenant_id, knowledge_base_id, title, created_by, updated_by)
            VALUES
              (#{tenantId}, #{knowledgeBaseId}, #{title}, #{createdBy}, #{updatedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDocument(DocumentEntity entity);

    @Insert("""
            INSERT INTO kb_document_version
              (tenant_id, knowledge_base_id, document_id, version_number, status,
               original_filename, file_extension, mime_type, file_size, source_sha256,
               upload_request_id, source_object_key, created_by)
            VALUES
              (#{tenantId}, #{knowledgeBaseId}, #{documentId}, #{versionNumber}, #{status},
               #{originalFilename}, #{fileExtension}, #{mimeType}, #{fileSize}, #{sourceSha256},
               #{uploadRequestId}, #{sourceObjectKey}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertVersion(DocumentVersionEntity entity);

    @Update("""
            UPDATE kb_document_version
               SET source_object_key = #{sourceObjectKey}
             WHERE tenant_id = #{tenantId}
               AND document_id = #{documentId}
               AND id = #{versionId}
            """)
    int updateSourceObjectKey(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("sourceObjectKey") String sourceObjectKey);

    @Update("""
            UPDATE kb_document
               SET current_draft_version_id = #{versionId},
                   updated_by = #{actorUserId},
                   row_version = row_version + 1
             WHERE tenant_id = #{tenantId}
               AND id = #{documentId}
               AND is_deleted = 0
            """)
    int updateDraftPointer(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("actorUserId") long actorUserId);

    @Select("""
            SELECT id, tenant_id, knowledge_base_id, title,
                   current_draft_version_id, current_published_version_id,
                   created_by, updated_by, row_version, created_at, updated_at
              FROM kb_document
             WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId}
               AND id=#{documentId} AND is_deleted=0
             FOR UPDATE
            """)
    DocumentEntity lockDocument(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId);

    @Select("""
            SELECT COALESCE(MAX(version_number),0)+1
              FROM kb_document_version
             WHERE tenant_id=#{tenantId} AND document_id=#{documentId}
            """)
    int nextVersionNumber(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId);

    @Select("""
            SELECT id, tenant_id, knowledge_base_id, document_id, version_number, status,
                   original_filename, file_extension, mime_type, file_size, source_sha256,
                   upload_request_id, source_object_key, parsed_object_key, parser_version,
                   chunk_strategy_version, embedding_model, embedding_dimension,
                   embedding_instruction_version, index_manifest_sha256, indexed_at,
                   ocr_required, correction_revision, unit_count, chunk_count, failure_stage,
                   last_error_code, last_error_message, created_by, created_at, updated_at
              FROM kb_document_version
             WHERE tenant_id=#{tenantId} AND upload_request_id=#{requestId}
            """)
    DocumentVersionEntity findVersionByUploadRequest(
            @Param("tenantId") long tenantId,
            @Param("requestId") String requestId);

    @Select("""
            SELECT id, tenant_id, knowledge_base_id, document_id, version_number, status,
                   original_filename, file_extension, mime_type, file_size, source_sha256,
                   upload_request_id, source_object_key, parsed_object_key, parser_version,
                   chunk_strategy_version, embedding_model, embedding_dimension,
                   embedding_instruction_version, index_manifest_sha256, indexed_at,
                   ocr_required, correction_revision, unit_count, chunk_count, failure_stage,
                   last_error_code, last_error_message, created_by, created_at, updated_at
              FROM kb_document_version
             WHERE tenant_id=#{tenantId} AND document_id=#{documentId} AND source_sha256=#{sourceSha256}
             ORDER BY id DESC LIMIT 1
            """)
    DocumentVersionEntity findVersionByContent(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("sourceSha256") String sourceSha256);

    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM kb_document
             WHERE tenant_id=#{tenantId} AND id=#{documentId}
               AND NOT EXISTS (
                   SELECT 1 FROM kb_document_version v
                    WHERE v.tenant_id=#{tenantId} AND v.document_id=#{documentId})
            """)
    int deleteEmptyDocument(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId);

    @Insert("""
            INSERT INTO kb_ingestion_task
              (tenant_id, knowledge_base_id, document_id, version_id, task_key, stage, status)
            VALUES
              (#{tenantId}, #{knowledgeBaseId}, #{documentId}, #{versionId},
               #{taskKey}, 'PARSE', 'PENDING')
            """)
    int insertInitialTask(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId,
            @Param("taskKey") String taskKey);

    @Insert("""
            INSERT INTO kb_outbox_event
              (event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload_json)
            VALUES
              (#{eventId}, #{tenantId}, 'DOCUMENT_VERSION', #{aggregateId},
               'DOCUMENT_INGESTION_REQUESTED', CAST(#{payloadJson} AS JSON))
            """)
    int insertInitialOutbox(
            @Param("eventId") String eventId,
            @Param("tenantId") long tenantId,
            @Param("aggregateId") String aggregateId,
            @Param("payloadJson") String payloadJson);

    @Select("""
            SELECT COUNT(*)
              FROM kb_ingestion_task
             WHERE tenant_id = #{tenantId}
               AND version_id = #{versionId}
            """)
    long countTasksForVersion(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId);

    @Select("""
            SELECT COUNT(*)
              FROM kb_outbox_event
             WHERE tenant_id = #{tenantId}
               AND aggregate_type = 'DOCUMENT_VERSION'
               AND aggregate_id = CAST(#{versionId} AS CHAR)
               AND status = 'PENDING'
            """)
    long countPendingOutboxForVersion(
            @Param("tenantId") long tenantId,
            @Param("versionId") long versionId);

    @Select("""
            SELECT id, tenant_id, knowledge_base_id, title,
                   current_draft_version_id, current_published_version_id,
                   created_by, updated_by, row_version, created_at, updated_at
              FROM kb_document
             WHERE tenant_id = #{tenantId}
               AND id = #{documentId}
               AND is_deleted = 0
            """)
    DocumentEntity findDocument(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId);

    @Select("""
            SELECT id, tenant_id, knowledge_base_id, document_id, version_number, status,
                   original_filename, file_extension, mime_type, file_size, source_sha256,
                   upload_request_id, source_object_key, parsed_object_key, parser_version, chunk_strategy_version,
                   embedding_model, embedding_dimension, embedding_instruction_version,
                   index_manifest_sha256, indexed_at,
                   ocr_required, correction_revision, unit_count, chunk_count, failure_stage,
                   last_error_code, last_error_message, created_by, created_at, updated_at
              FROM kb_document_version
             WHERE tenant_id = #{tenantId}
               AND document_id = #{documentId}
               AND id = #{versionId}
            """)
    DocumentVersionEntity findVersion(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId);

    @Select("""
            SELECT id, tenant_id, knowledge_base_id, title,
                   current_draft_version_id, current_published_version_id,
                   created_by, updated_by, row_version, created_at, updated_at
              FROM kb_document
             WHERE tenant_id = #{tenantId}
               AND knowledge_base_id = #{knowledgeBaseId}
               AND is_deleted = 0
             ORDER BY id DESC
            """)
    List<DocumentEntity> listDocuments(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId);

    @Select("""
            SELECT id, tenant_id, knowledge_base_id, document_id, version_number, status,
                   original_filename, file_extension, mime_type, file_size, source_sha256,
                   upload_request_id, source_object_key, parsed_object_key, parser_version, chunk_strategy_version,
                   embedding_model, embedding_dimension, embedding_instruction_version,
                   index_manifest_sha256, indexed_at,
                   ocr_required, correction_revision, unit_count, chunk_count, failure_stage,
                   last_error_code, last_error_message, created_by, created_at, updated_at
              FROM kb_document_version
             WHERE tenant_id=#{tenantId} AND document_id=#{documentId}
             ORDER BY version_number DESC
            """)
    List<DocumentVersionEntity> listVersions(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId);
}

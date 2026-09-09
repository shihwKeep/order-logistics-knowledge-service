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
               source_object_key, created_by)
            VALUES
              (#{tenantId}, #{knowledgeBaseId}, #{documentId}, #{versionNumber}, #{status},
               #{originalFilename}, #{fileExtension}, #{mimeType}, #{fileSize}, #{sourceSha256},
               #{sourceObjectKey}, #{createdBy})
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
                   source_object_key, parsed_object_key, parser_version, chunk_strategy_version,
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
}

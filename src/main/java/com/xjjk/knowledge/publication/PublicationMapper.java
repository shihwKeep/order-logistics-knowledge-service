package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.document.persistence.DocumentEntity;
import com.xjjk.knowledge.document.persistence.DocumentVersionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PublicationMapper {
    String VERSION_COLUMNS = """
            id,tenant_id,knowledge_base_id,document_id,version_number,status,original_filename,
            file_extension,mime_type,file_size,source_sha256,source_object_key,parsed_object_key,
            parser_version,chunk_strategy_version,embedding_model,embedding_dimension,
            embedding_instruction_version,index_manifest_sha256,indexed_at,ocr_required,
            correction_revision,unit_count,chunk_count,failure_stage,last_error_code,last_error_message,
            created_by,created_at,updated_at
            """;

    @Select("""
            SELECT id,tenant_id,knowledge_base_id,title,current_draft_version_id,current_published_version_id,
                   created_by,updated_by,row_version,created_at,updated_at
              FROM kb_document
             WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId}
               AND id=#{documentId} AND is_deleted=0
            """)
    DocumentEntity findDocument(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
                                @Param("documentId") long documentId);

    @Select("""
            SELECT id,tenant_id,knowledge_base_id,title,current_draft_version_id,current_published_version_id,
                   created_by,updated_by,row_version,created_at,updated_at
              FROM kb_document
             WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId}
               AND id=#{documentId} AND is_deleted=0 FOR UPDATE
            """)
    DocumentEntity lockDocument(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
                                @Param("documentId") long documentId);

    @Select("SELECT " + VERSION_COLUMNS + " FROM kb_document_version WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId} AND document_id=#{documentId} AND id=#{versionId}")
    DocumentVersionEntity findVersion(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
                                      @Param("documentId") long documentId, @Param("versionId") long versionId);

    @Select("SELECT " + VERSION_COLUMNS + " FROM kb_document_version WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId} AND document_id=#{documentId} AND id=#{versionId} FOR UPDATE")
    DocumentVersionEntity lockVersion(@Param("tenantId") long tenantId, @Param("knowledgeBaseId") long knowledgeBaseId,
                                      @Param("documentId") long documentId, @Param("versionId") long versionId);

    @Select("""
            SELECT id,tenant_id,knowledge_base_id,document_id,from_version_id,to_version_id,action,
                   actor_user_id,request_id,chunk_count,manifest_sha256,created_at
              FROM kb_publish_record WHERE tenant_id=#{tenantId} AND request_id=#{requestId}
            """)
    PublicationRecordEntity findRecord(@Param("tenantId") long tenantId, @Param("requestId") String requestId);

    @Select("""
            SELECT id,tenant_id,knowledge_base_id,document_id,from_version_id,to_version_id,action,
                   actor_user_id,request_id,chunk_count,manifest_sha256,created_at
              FROM kb_publish_record
             WHERE tenant_id=#{tenantId} AND request_id=#{requestId}
             FOR UPDATE
            """)
    PublicationRecordEntity findRecordForUpdate(
            @Param("tenantId") long tenantId, @Param("requestId") String requestId);

    @Update("""
            UPDATE kb_document SET current_published_version_id=#{versionId},updated_by=#{actorUserId},
                   row_version=row_version+1
             WHERE tenant_id=#{tenantId} AND id=#{documentId} AND row_version=#{expectedRowVersion} AND is_deleted=0
            """)
    int switchPointer(@Param("tenantId") long tenantId, @Param("documentId") long documentId,
                      @Param("versionId") Long versionId, @Param("actorUserId") long actorUserId,
                      @Param("expectedRowVersion") int expectedRowVersion);

    @Update("UPDATE kb_document_version SET status=#{status} WHERE tenant_id=#{tenantId} AND document_id=#{documentId} AND id=#{versionId}")
    int updateVersionStatus(@Param("tenantId") long tenantId, @Param("documentId") long documentId,
                            @Param("versionId") long versionId, @Param("status") String status);

    @Insert("""
            INSERT INTO kb_publish_record
              (tenant_id,knowledge_base_id,document_id,from_version_id,to_version_id,action,
               actor_user_id,request_id,chunk_count,manifest_sha256)
            VALUES
              (#{tenantId},#{knowledgeBaseId},#{documentId},#{fromVersionId},#{toVersionId},#{action},
               #{actorUserId},#{requestId},#{chunkCount},#{manifestSha256})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRecord(PublicationRecordEntity entity);

    @Insert("""
            INSERT IGNORE INTO kb_published_index_cleanup
              (tenant_id,publish_record_id,document_id,version_id,status)
            VALUES(#{tenantId},#{publishRecordId},#{documentId},#{versionId},'PENDING')
            """)
    int insertCleanup(@Param("tenantId") long tenantId, @Param("publishRecordId") long publishRecordId,
                      @Param("documentId") long documentId, @Param("versionId") long versionId);

    @Update("""
            UPDATE kb_published_index_cleanup
               SET status='CANCELED',last_error_code=NULL
             WHERE tenant_id=#{tenantId} AND document_id=#{documentId} AND version_id=#{versionId}
               AND status IN ('PENDING','RETRY')
            """)
    int cancelCleanupForVersion(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId);

    /**
     * 与发布/回滚使用相同的文档行锁。清理事务持锁期间，发布指针不能切换到待删除版本。
     * 返回 null 既可能表示文档未发布，也可能表示文档不存在；两种情况都允许清理派生索引。
     */
    @Select("""
            SELECT current_published_version_id
              FROM kb_document
             WHERE tenant_id=#{tenantId} AND id=#{documentId}
             FOR UPDATE
            """)
    Long lockCurrentPublishedVersion(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId);

    @Select("""
            SELECT id,tenant_id,document_id,version_id,retry_count
              FROM kb_published_index_cleanup
             WHERE status IN ('PENDING','RETRY') AND next_run_at<=CURRENT_TIMESTAMP(3)
             ORDER BY id LIMIT #{limit}
            """)
    List<PublicationCleanupTask> findDueCleanup(@Param("limit") int limit);

    @Update("UPDATE kb_published_index_cleanup SET status='DONE',last_error_code=NULL WHERE id=#{id} AND status IN ('PENDING','RETRY')")
    int completeCleanup(@Param("id") long id);

    @Update("""
            UPDATE kb_published_index_cleanup
               SET status='RETRY',retry_count=retry_count+1,next_run_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 30 SECOND),
                   last_error_code=#{errorCode}
             WHERE id=#{id} AND status IN ('PENDING','RETRY')
            """)
    int failCleanup(@Param("id") long id, @Param("errorCode") String errorCode);
}

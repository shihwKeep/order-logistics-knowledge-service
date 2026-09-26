package com.xjjk.knowledge.publication.release;

import com.xjjk.knowledge.document.persistence.DocumentVersionEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReleaseMapper {
    String RELEASE_COLUMNS = """
            id,tenant_id,knowledge_base_id,release_number,status,base_release_id,request_id,
            manifest_sha256,base_row_version,created_by,failure_code,created_at,activated_at,updated_at
            """;
    String VERSION_COLUMNS = """
            v.id,v.tenant_id,v.knowledge_base_id,v.document_id,v.version_number,v.status,
            v.original_filename,v.file_extension,v.mime_type,v.file_size,v.source_sha256,
            v.source_object_key,v.parsed_object_key,v.parser_version,v.chunk_strategy_version,
            v.embedding_model,v.embedding_dimension,v.embedding_instruction_version,
            v.index_manifest_sha256,v.indexed_at,v.ocr_required,v.correction_revision,
            v.unit_count,v.chunk_count,v.failure_stage,v.last_error_code,v.last_error_message,
            v.created_by,v.created_at,v.updated_at
            """;

    @Select("""
            SELECT kb.current_release_id,kb.row_version,
                   active.manifest_sha256 AS current_manifest_sha256
              FROM kb_knowledge_base kb
              LEFT JOIN kb_release active
                ON active.id=kb.current_release_id AND active.tenant_id=kb.tenant_id
             WHERE kb.tenant_id=#{tenantId} AND kb.id=#{knowledgeBaseId} AND kb.is_deleted=0
             FOR UPDATE
            """)
    ReleaseBaseline lockBaseline(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId);

    @Select("""
            SELECT release_id,tenant_id,knowledge_base_id,document_id,version_id,
                   content_manifest_sha256
              FROM kb_release_item
             WHERE release_id=#{releaseId}
             ORDER BY document_id
            """)
    List<ReleaseItem> items(@Param("releaseId") long releaseId);

    @Select("""
            SELECT 0 AS release_id,v.tenant_id,v.knowledge_base_id,v.document_id,
                   v.id AS version_id,v.index_manifest_sha256 AS content_manifest_sha256
              FROM kb_document_version v
              JOIN kb_document d
                ON d.tenant_id=v.tenant_id AND d.knowledge_base_id=v.knowledge_base_id
               AND d.id=v.document_id AND d.is_deleted=0
             WHERE v.tenant_id=#{tenantId} AND v.knowledge_base_id=#{knowledgeBaseId}
               AND v.document_id=#{documentId} AND v.id=#{versionId}
               AND v.status='READY' AND v.index_manifest_sha256 IS NOT NULL
            """)
    ReleaseItem findReadyItem(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId);

    @Select("""
            SELECT 0 AS release_id,v.tenant_id,v.knowledge_base_id,v.document_id,
                   v.id AS version_id,v.index_manifest_sha256 AS content_manifest_sha256
              FROM kb_document_version v
              JOIN kb_document d
                ON d.tenant_id=v.tenant_id AND d.knowledge_base_id=v.knowledge_base_id
               AND d.id=v.document_id AND d.is_deleted=0
             WHERE v.tenant_id=#{tenantId} AND v.knowledge_base_id=#{knowledgeBaseId}
               AND v.document_id=#{documentId} AND v.id=#{versionId}
               AND v.status IN ('READY','PUBLISHED','ARCHIVED')
               AND v.index_manifest_sha256 IS NOT NULL
            """)
    ReleaseItem findPublishableItem(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("documentId") long documentId,
            @Param("versionId") long versionId);

    @Select("SELECT " + RELEASE_COLUMNS + " FROM kb_release WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    KnowledgeRelease findByRequest(
            @Param("tenantId") long tenantId,
            @Param("requestId") String requestId);

    @Select("SELECT " + RELEASE_COLUMNS + " FROM kb_release WHERE id=#{releaseId}")
    KnowledgeRelease findById(@Param("releaseId") long releaseId);

    @Select("""
            SELECT id,tenant_id,knowledge_base_id,release_number,status,base_release_id,request_id,
                   manifest_sha256,base_row_version,created_by,failure_code,created_at,activated_at,updated_at
              FROM kb_release
             WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId} AND id=#{releaseId}
            """)
    KnowledgeRelease find(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("releaseId") long releaseId);

    @Select("""
            SELECT id,tenant_id,knowledge_base_id,release_number,status,base_release_id,request_id,
                   manifest_sha256,base_row_version,created_by,failure_code,created_at,activated_at,updated_at
              FROM kb_release
             WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId}
             ORDER BY release_number DESC
            """)
    List<KnowledgeRelease> list(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId);

    @Select("""
            SELECT id,tenant_id,knowledge_base_id,release_number,status,base_release_id,request_id,
                   manifest_sha256,base_row_version,created_by,failure_code,created_at,activated_at,updated_at
              FROM kb_release
             WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId} AND id=#{releaseId}
             FOR UPDATE
            """)
    KnowledgeRelease lockRelease(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("releaseId") long releaseId);

    @Select("""
            SELECT
            """ + VERSION_COLUMNS + """
              FROM kb_release_item target
              JOIN kb_document_version v
                ON v.tenant_id=target.tenant_id AND v.id=target.version_id
              LEFT JOIN kb_release_item base
                ON base.release_id=#{baseReleaseId} AND base.document_id=target.document_id
             WHERE target.release_id=#{releaseId}
               AND (base.version_id IS NULL OR base.version_id<>target.version_id)
             ORDER BY target.document_id
            """)
    List<DocumentVersionEntity> changedVersions(
            @Param("releaseId") long releaseId,
            @Param("baseReleaseId") Long baseReleaseId);

    @Select("""
            SELECT current_release_id FROM kb_knowledge_base
             WHERE tenant_id=#{tenantId} AND id=#{knowledgeBaseId} AND is_deleted=0
            """)
    Long currentReleaseId(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId);

    @Select("""
            SELECT COUNT(*)
              FROM kb_release_item item
              LEFT JOIN kb_document_version v
                ON v.tenant_id=item.tenant_id AND v.knowledge_base_id=item.knowledge_base_id
               AND v.document_id=item.document_id AND v.id=item.version_id
             WHERE item.release_id=#{releaseId}
               AND (v.id IS NULL OR v.status NOT IN ('READY','PUBLISHED','ARCHIVED')
                    OR v.index_manifest_sha256<>item.content_manifest_sha256)
            """)
    int countInvalidItems(@Param("releaseId") long releaseId);

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM kb_release_task
                 WHERE id=#{lease.taskId} AND release_id=#{lease.releaseId}
                   AND tenant_id=#{lease.tenantId} AND knowledge_base_id=#{lease.knowledgeBaseId}
                   AND status='PROCESSING' AND lease_token=#{lease.leaseToken}
                   AND locked_until>=CURRENT_TIMESTAMP(3))
            """)
    boolean isLeaseValid(@Param("lease") ReleaseTaskLease lease);

    @Update("""
            UPDATE kb_knowledge_base
               SET current_release_id=#{releaseId},updated_by=#{actorUserId},row_version=row_version+1
             WHERE tenant_id=#{tenantId} AND id=#{knowledgeBaseId} AND is_deleted=0
               AND row_version=#{baseRowVersion}
               AND ((#{baseReleaseId} IS NULL AND current_release_id IS NULL)
                    OR current_release_id=#{baseReleaseId})
            """)
    int switchCurrentRelease(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("releaseId") long releaseId,
            @Param("baseReleaseId") Long baseReleaseId,
            @Param("baseRowVersion") int baseRowVersion,
            @Param("actorUserId") long actorUserId);

    @Update("UPDATE kb_release SET status='SUPERSEDED' WHERE id=#{releaseId} AND status='ACTIVE'")
    int markSuperseded(@Param("releaseId") long releaseId);

    @Update("""
            UPDATE kb_release
               SET status='ACTIVE',activated_at=CURRENT_TIMESTAMP(3),failure_code=NULL
             WHERE id=#{releaseId} AND status='PREPARING'
            """)
    int markActive(@Param("releaseId") long releaseId);

    @Update("""
            UPDATE kb_document d
              LEFT JOIN kb_release_item item
                ON item.release_id=#{releaseId} AND item.document_id=d.id
               AND item.tenant_id=d.tenant_id AND item.knowledge_base_id=d.knowledge_base_id
               SET d.current_published_version_id=item.version_id,
                   d.updated_by=#{actorUserId},d.row_version=d.row_version+1
             WHERE d.tenant_id=#{tenantId} AND d.knowledge_base_id=#{knowledgeBaseId}
               AND d.is_deleted=0
            """)
    int syncDocumentPointers(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("releaseId") long releaseId,
            @Param("actorUserId") long actorUserId);

    @Update("""
            UPDATE kb_document_version v
              JOIN kb_release_item item
                ON item.release_id=#{releaseId} AND item.tenant_id=v.tenant_id AND item.version_id=v.id
               SET v.status='PUBLISHED'
             WHERE v.status IN ('READY','PUBLISHED','ARCHIVED')
            """)
    int markItemsPublished(@Param("releaseId") long releaseId);

    @Update("""
            UPDATE kb_document_version v
              JOIN kb_release_item old_item
                ON old_item.release_id=#{baseReleaseId} AND old_item.tenant_id=v.tenant_id
               AND old_item.version_id=v.id
              LEFT JOIN kb_release_item new_item
                ON new_item.release_id=#{releaseId} AND new_item.document_id=old_item.document_id
               SET v.status='ARCHIVED'
             WHERE (new_item.version_id IS NULL OR new_item.version_id<>old_item.version_id)
               AND v.status='PUBLISHED'
            """)
    int archiveReplacedItems(
            @Param("baseReleaseId") Long baseReleaseId,
            @Param("releaseId") long releaseId);

    @Insert("""
            INSERT IGNORE INTO kb_derived_index_cleanup(
              tenant_id,knowledge_base_id,document_id,version_id,index_layer,cleanup_reason,status)
            SELECT old_item.tenant_id,old_item.knowledge_base_id,old_item.document_id,
                   old_item.version_id,'PUBLISHED','RELEASE_SUPERSEDED','PENDING'
              FROM kb_release_item old_item
              LEFT JOIN kb_release_item new_item
                ON new_item.release_id=#{releaseId} AND new_item.document_id=old_item.document_id
             WHERE old_item.release_id=#{baseReleaseId}
               AND (new_item.version_id IS NULL OR new_item.version_id<>old_item.version_id)
            """)
    int insertSupersededCleanup(
            @Param("baseReleaseId") Long baseReleaseId,
            @Param("releaseId") long releaseId);

    @Update("""
            UPDATE kb_release SET status=#{status},failure_code=#{failureCode}
             WHERE id=#{releaseId} AND status='PREPARING'
            """)
    int markTerminal(
            @Param("releaseId") long releaseId,
            @Param("status") String status,
            @Param("failureCode") String failureCode);

    @Insert("""
            INSERT IGNORE INTO kb_derived_index_cleanup(
              tenant_id,knowledge_base_id,document_id,version_id,index_layer,cleanup_reason,status)
            SELECT target.tenant_id,target.knowledge_base_id,target.document_id,target.version_id,
                   'PUBLISHED','RELEASE_FAILED','PENDING'
              FROM kb_release_item target
              LEFT JOIN kb_release_item base
                ON base.release_id=#{baseReleaseId} AND base.document_id=target.document_id
             WHERE target.release_id=#{releaseId}
               AND (base.version_id IS NULL OR base.version_id<>target.version_id)
            """)
    int insertFailedCleanup(
            @Param("releaseId") long releaseId,
            @Param("baseReleaseId") Long baseReleaseId);

    @Select("""
            SELECT COALESCE(MAX(release_number),0)+1
              FROM kb_release
             WHERE tenant_id=#{tenantId} AND knowledge_base_id=#{knowledgeBaseId}
            """)
    int nextReleaseNumber(
            @Param("tenantId") long tenantId,
            @Param("knowledgeBaseId") long knowledgeBaseId);

    @Insert("""
            INSERT INTO kb_release(
              tenant_id,knowledge_base_id,release_number,status,base_release_id,request_id,
              manifest_sha256,base_row_version,created_by,failure_code)
            VALUES(
              #{tenantId},#{knowledgeBaseId},#{releaseNumber},#{status},#{baseReleaseId},#{requestId},
              #{manifestSha256},#{baseRowVersion},#{createdBy},#{failureCode})
            """)
    int insertRelease(KnowledgeRelease release);

    @Insert("""
            INSERT INTO kb_release_item(
              release_id,tenant_id,knowledge_base_id,document_id,version_id,content_manifest_sha256)
            VALUES(
              #{releaseId},#{item.tenantId},#{item.knowledgeBaseId},#{item.documentId},
              #{item.versionId},#{item.contentManifestSha256})
            """)
    int insertItem(@Param("releaseId") long releaseId, @Param("item") ReleaseItem item);

    @Insert("""
            INSERT INTO kb_release_task(release_id,tenant_id,knowledge_base_id,status)
            VALUES(#{id},#{tenantId},#{knowledgeBaseId},'PENDING')
            """)
    int insertTask(KnowledgeRelease release);

    @Insert("""
            INSERT INTO kb_outbox_event(
              event_id,tenant_id,aggregate_type,aggregate_id,event_type,payload_json)
            VALUES(
              #{eventId},#{release.tenantId},'KNOWLEDGE_RELEASE',#{aggregateId},
              'KNOWLEDGE_RELEASE_REQUESTED',CAST(#{payloadJson} AS JSON))
            """)
    int insertOutbox(
            @Param("eventId") String eventId,
            @Param("aggregateId") String aggregateId,
            @Param("payloadJson") String payloadJson,
            @Param("release") KnowledgeRelease release);
}

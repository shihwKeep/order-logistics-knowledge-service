package com.xjjk.knowledge.publication.release;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReleaseMapper {
    String RELEASE_COLUMNS = """
            id,tenant_id,knowledge_base_id,release_number,status,base_release_id,request_id,
            manifest_sha256,base_row_version,created_by,failure_code,created_at,activated_at,updated_at
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

    @Select("SELECT " + RELEASE_COLUMNS + " FROM kb_release WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    KnowledgeRelease findByRequest(
            @Param("tenantId") long tenantId,
            @Param("requestId") String requestId);

    @Select("SELECT " + RELEASE_COLUMNS + " FROM kb_release WHERE id=#{releaseId}")
    KnowledgeRelease findById(@Param("releaseId") long releaseId);

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

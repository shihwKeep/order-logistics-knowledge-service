package com.xjjk.knowledge.publication.cleanup;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DerivedCleanupMapper {
    @Select("""
            SELECT id
              FROM kb_derived_index_cleanup
             WHERE ((status IN ('PENDING','RETRY') AND next_run_at<=CURRENT_TIMESTAMP(3))
                    OR (status='PROCESSING' AND locked_until<CURRENT_TIMESTAMP(3)))
             ORDER BY next_run_at,id
             LIMIT #{limit}
            """)
    List<Long> findDueIds(@Param("limit") int limit);

    @Update("""
            UPDATE kb_derived_index_cleanup
               SET status='PROCESSING',lease_token=#{leaseToken},locked_by=#{workerId},
                   locked_until=#{lockedUntil},updated_at=CURRENT_TIMESTAMP(3)
             WHERE id=#{id}
               AND ((status IN ('PENDING','RETRY') AND next_run_at<=CURRENT_TIMESTAMP(3))
                    OR (status='PROCESSING' AND locked_until<CURRENT_TIMESTAMP(3)))
            """)
    int claim(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("workerId") String workerId,
            @Param("lockedUntil") LocalDateTime lockedUntil);

    @Select("""
            SELECT id,tenant_id,knowledge_base_id,document_id,version_id,index_layer AS layer,
                   cleanup_reason AS reason,retry_count,lease_token
              FROM kb_derived_index_cleanup
             WHERE id=#{id}
            """)
    DerivedCleanupTask find(@Param("id") long id);

    @Select("""
            SELECT CASE WHEN
                 EXISTS (
                    SELECT 1 FROM kb_document d
                     WHERE d.tenant_id=#{task.tenantId} AND d.knowledge_base_id=#{task.knowledgeBaseId}
                       AND d.id=#{task.documentId} AND d.is_deleted=0
                       AND d.current_draft_version_id=#{task.versionId})
                 OR EXISTS (
                    SELECT 1
                      FROM kb_knowledge_base kb
                      JOIN kb_release_item item
                        ON item.release_id=kb.current_release_id
                       AND item.tenant_id=kb.tenant_id
                       AND item.knowledge_base_id=kb.id
                     WHERE kb.tenant_id=#{task.tenantId} AND kb.id=#{task.knowledgeBaseId}
                       AND item.document_id=#{task.documentId}
                       AND item.version_id=#{task.versionId})
                 THEN 1 ELSE 0 END
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    boolean isReferenced(@Param("task") DerivedCleanupTask task);

    @Update("""
            UPDATE kb_derived_index_cleanup
               SET status='DONE',lease_token=NULL,locked_by=NULL,locked_until=NULL,
                   last_error_code=NULL,updated_at=CURRENT_TIMESTAMP(3)
             WHERE id=#{id} AND status='PROCESSING' AND lease_token=#{leaseToken}
            """)
    int completeOwned(@Param("id") long id, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE kb_derived_index_cleanup
               SET status=#{status},retry_count=retry_count+1,next_run_at=#{nextRunAt},
                   lease_token=NULL,locked_by=NULL,locked_until=NULL,
                   last_error_code=#{errorCode},updated_at=CURRENT_TIMESTAMP(3)
             WHERE id=#{id} AND status='PROCESSING' AND lease_token=#{leaseToken}
            """)
    int retryOwned(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("status") String status,
            @Param("nextRunAt") LocalDateTime nextRunAt,
            @Param("errorCode") String errorCode);
}

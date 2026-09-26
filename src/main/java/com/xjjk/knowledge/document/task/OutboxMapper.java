package com.xjjk.knowledge.document.task;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OutboxMapper {
    @Select("""
            SELECT o.id,COALESCE(t.id,rt.id) AS task_id,o.event_type,o.attempt_count
              FROM kb_outbox_event o
              LEFT JOIN kb_ingestion_task t
                ON o.event_type='DOCUMENT_INGESTION_REQUESTED' AND t.id=(
                    SELECT MAX(candidate.id) FROM kb_ingestion_task candidate
                     WHERE candidate.tenant_id=o.tenant_id
                       AND candidate.version_id=CAST(o.aggregate_id AS UNSIGNED))
              LEFT JOIN kb_release_task rt
                ON o.event_type='KNOWLEDGE_RELEASE_REQUESTED'
               AND rt.release_id=CAST(o.aggregate_id AS UNSIGNED)
             WHERE o.status IN ('PENDING','RETRY') AND o.next_attempt_at<=CURRENT_TIMESTAMP(3)
               AND (t.id IS NOT NULL OR rt.id IS NOT NULL)
             ORDER BY o.id LIMIT #{limit}
            """)
    List<OutboxEvent> findPending(@Param("limit") int limit);

    @Update("""
            UPDATE kb_outbox_event SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(3),
                   last_error_code=NULL,updated_at=CURRENT_TIMESTAMP(3)
             WHERE id=#{id} AND status IN ('PENDING','RETRY')
            """)
    int markPublished(@Param("id") long id);

    @Update("""
            UPDATE kb_outbox_event SET status='RETRY',attempt_count=attempt_count+1,
                   next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 10 SECOND),
                   last_error_code='RABBIT_PUBLISH_FAILED',updated_at=CURRENT_TIMESTAMP(3)
             WHERE id=#{id} AND status IN ('PENDING','RETRY')
            """)
    int markRetry(@Param("id") long id);
}

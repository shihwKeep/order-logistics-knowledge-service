package com.xjjk.knowledge.publication.release;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReleaseTaskMapper {
    @Select("""
            SELECT id FROM kb_release_task
             WHERE ((status IN ('PENDING','RETRY') AND next_run_at<=CURRENT_TIMESTAMP(3))
                    OR (status='PROCESSING' AND locked_until<CURRENT_TIMESTAMP(3)))
             ORDER BY next_run_at,id LIMIT #{limit}
            """)
    List<Long> findDueIds(@Param("limit") int limit);

    @Update("""
            UPDATE kb_release_task
               SET status='PROCESSING',lease_token=#{leaseToken},locked_by=#{workerId},
                   locked_until=#{lockedUntil},updated_at=CURRENT_TIMESTAMP(3)
             WHERE id=#{taskId}
               AND ((status IN ('PENDING','RETRY') AND next_run_at<=CURRENT_TIMESTAMP(3))
                    OR (status='PROCESSING' AND locked_until<CURRENT_TIMESTAMP(3)))
            """)
    int claim(
            @Param("taskId") long taskId,
            @Param("leaseToken") String leaseToken,
            @Param("workerId") String workerId,
            @Param("lockedUntil") LocalDateTime lockedUntil);

    @Select("""
            SELECT id,release_id,tenant_id,knowledge_base_id,status,retry_count,next_run_at,
                   lease_token,locked_by,locked_until,last_error_code
              FROM kb_release_task WHERE id=#{taskId}
            """)
    ReleaseTask find(@Param("taskId") long taskId);

    @Update("""
            UPDATE kb_release_task SET locked_until=#{lockedUntil},updated_at=CURRENT_TIMESTAMP(3)
             WHERE id=#{taskId} AND status='PROCESSING' AND lease_token=#{leaseToken}
            """)
    int renew(
            @Param("taskId") long taskId,
            @Param("leaseToken") String leaseToken,
            @Param("lockedUntil") LocalDateTime lockedUntil);

    @Update("""
            UPDATE kb_release_task
               SET status='DONE',lease_token=NULL,locked_by=NULL,locked_until=NULL,
                   last_error_code=NULL,last_error_message=NULL,updated_at=CURRENT_TIMESTAMP(3)
             WHERE id=#{taskId} AND status='PROCESSING' AND lease_token=#{leaseToken}
            """)
    int complete(@Param("taskId") long taskId, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE kb_release_task
               SET status=#{status},retry_count=retry_count+1,next_run_at=#{nextRunAt},
                   lease_token=NULL,locked_by=NULL,locked_until=NULL,
                   last_error_code=#{errorCode},last_error_message=#{errorMessage},
                   updated_at=CURRENT_TIMESTAMP(3)
             WHERE id=#{taskId} AND status='PROCESSING' AND lease_token=#{leaseToken}
            """)
    int retryOrFail(
            @Param("taskId") long taskId,
            @Param("leaseToken") String leaseToken,
            @Param("status") String status,
            @Param("nextRunAt") LocalDateTime nextRunAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage);
}

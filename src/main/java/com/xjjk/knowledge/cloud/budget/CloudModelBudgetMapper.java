package com.xjjk.knowledge.cloud.budget;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CloudModelBudgetMapper {
    @Insert("""
        INSERT IGNORE INTO knowledge_cloud_model_budget
          (billing_month, hard_limit_micros, settled_micros, reserved_micros,
           version, created_at, updated_at)
        VALUES (#{month}, #{hardLimitMicros}, 0, 0, 0, #{now}, #{now})
        """)
    int insertMonthIfAbsent(@Param("month") String month,
                            @Param("hardLimitMicros") long hardLimitMicros,
                            @Param("now") LocalDateTime now);

    @Update("""
        UPDATE knowledge_cloud_model_budget
        SET reserved_micros = reserved_micros + #{micros},
            version = version + 1, updated_at = #{now}
        WHERE billing_month = #{month}
          AND settled_micros + reserved_micros + #{micros} <= hard_limit_micros
        """)
    int reserve(@Param("month") String month, @Param("micros") long micros,
                @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO knowledge_cloud_model_call
          (call_id, billing_month, logical_request_id, attempt_no, call_type,
           model_name, status, reserved_micros, created_at, updated_at)
        VALUES (#{callId}, #{month}, #{logicalRequestId}, #{attemptNo}, #{callType},
                #{model}, 'RESERVED', #{reservedMicros}, #{now}, #{now})
        """)
    int insertCall(@Param("callId") String callId, @Param("month") String month,
                   @Param("logicalRequestId") String logicalRequestId,
                   @Param("attemptNo") int attemptNo, @Param("callType") String callType,
                   @Param("model") String model, @Param("reservedMicros") long reservedMicros,
                   @Param("now") LocalDateTime now);

    @Update("""
        UPDATE knowledge_cloud_model_budget
        SET reserved_micros = reserved_micros - #{reservedMicros},
            settled_micros = settled_micros + #{settledMicros},
            version = version + 1, updated_at = #{now}
        WHERE billing_month = #{month} AND reserved_micros >= #{reservedMicros}
        """)
    int settleAccount(@Param("month") String month,
                      @Param("reservedMicros") long reservedMicros,
                      @Param("settledMicros") long settledMicros,
                      @Param("now") LocalDateTime now);

    @Update("""
        UPDATE knowledge_cloud_model_budget
        SET reserved_micros = reserved_micros - #{reservedMicros},
            version = version + 1, updated_at = #{now}
        WHERE billing_month = #{month} AND reserved_micros >= #{reservedMicros}
        """)
    int releaseAccount(@Param("month") String month,
                       @Param("reservedMicros") long reservedMicros,
                       @Param("now") LocalDateTime now);

    @Update("""
        UPDATE knowledge_cloud_model_call
        SET status = 'SETTLED', settled_micros = #{settledMicros},
            total_tokens = #{totalTokens}, provider_request_id = #{providerRequestId},
            updated_at = #{now}
        WHERE call_id = #{callId} AND status = 'RESERVED'
        """)
    int markSettled(@Param("callId") String callId,
                    @Param("settledMicros") long settledMicros,
                    @Param("totalTokens") long totalTokens,
                    @Param("providerRequestId") String providerRequestId,
                    @Param("now") LocalDateTime now);

    @Update("""
        UPDATE knowledge_cloud_model_call
        SET status = 'RELEASED', error_code = #{errorCode}, updated_at = #{now}
        WHERE call_id = #{callId} AND status = 'RESERVED'
        """)
    int markReleased(@Param("callId") String callId, @Param("errorCode") String errorCode,
                     @Param("now") LocalDateTime now);

    @Update("""
        UPDATE knowledge_cloud_model_call
        SET status = 'UNKNOWN', error_code = #{errorCode}, updated_at = #{now}
        WHERE call_id = #{callId} AND status = 'RESERVED'
        """)
    int markUnknown(@Param("callId") String callId, @Param("errorCode") String errorCode,
                    @Param("now") LocalDateTime now);
}

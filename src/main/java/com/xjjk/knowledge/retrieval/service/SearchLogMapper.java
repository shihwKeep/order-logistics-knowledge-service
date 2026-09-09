package com.xjjk.knowledge.retrieval.service;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SearchLogMapper {
    @Insert("""
            INSERT INTO kb_search_log
              (tenant_id,user_id,request_id,strategy_version,degradation_mode,result_code,answerable,
               vector_candidate_count,keyword_candidate_count,fused_candidate_count,evidence_count,total_duration_ms)
            VALUES
              (#{tenantId},#{userId},#{requestId},#{strategyVersion},#{degradationMode},#{resultCode},#{answerable},
               #{vectorCandidateCount},#{keywordCandidateCount},#{fusedCandidateCount},#{evidenceCount},#{totalDurationMs})
            """)
    int insert(SearchLogEntry entry);
}

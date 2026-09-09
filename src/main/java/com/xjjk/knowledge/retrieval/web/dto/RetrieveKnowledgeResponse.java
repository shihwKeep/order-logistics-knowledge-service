package com.xjjk.knowledge.retrieval.web.dto;

import com.xjjk.knowledge.retrieval.model.DegradationMode;
import com.xjjk.knowledge.retrieval.model.RetrievalResult;

import java.util.List;

/** 稳定的知识检索响应，候选数量等内部诊断信息只写检索日志。 */
public record RetrieveKnowledgeResponse(
        boolean answerable,
        List<EvidenceResponse> evidences,
        String strategyVersion,
        DegradationMode degradationMode,
        String resultCode) {

    public static RetrieveKnowledgeResponse from(RetrievalResult result) {
        return new RetrieveKnowledgeResponse(
                result.answerable(),
                result.evidences().stream().map(EvidenceResponse::from).toList(),
                result.strategyVersion(), result.degradationMode(), result.resultCode());
    }
}

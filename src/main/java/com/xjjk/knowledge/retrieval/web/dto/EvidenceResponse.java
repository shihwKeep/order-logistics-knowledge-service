package com.xjjk.knowledge.retrieval.web.dto;

import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import com.xjjk.knowledge.retrieval.model.RecallSource;

import java.util.Set;

/** 返回给 Agent 的可引用证据，不暴露索引实现细节。 */
public record EvidenceResponse(
        long knowledgeBaseId,
        long documentId,
        long versionId,
        String chunkId,
        String documentTitle,
        String titlePath,
        String content,
        String locationJson,
        double score,
        Set<RecallSource> sources) {

    public static EvidenceResponse from(RankedEvidence evidence) {
        var chunk = evidence.chunk();
        return new EvidenceResponse(
                chunk.knowledgeBaseId(), chunk.documentId(), chunk.versionId(), chunk.chunkId(),
                chunk.documentTitle(), chunk.titlePath(), chunk.content(), chunk.locationJson(),
                evidence.score(), evidence.sources());
    }
}

package com.xjjk.knowledge.retrieval.rerank;

import com.xjjk.knowledge.retrieval.model.RankedEvidence;

import java.util.List;

public interface Reranker {
    List<RankedEvidence> rerank(String query, List<RankedEvidence> candidates);
}

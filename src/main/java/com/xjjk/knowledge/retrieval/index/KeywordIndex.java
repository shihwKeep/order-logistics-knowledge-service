package com.xjjk.knowledge.retrieval.index;

import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.RecallCandidate;

import java.util.List;

public interface KeywordIndex {
    void ensureReady();

    void replaceVersion(IndexLayer layer, List<IndexChunk> chunks);

    List<RecallCandidate> search(
            IndexLayer layer, long tenantId, List<Long> knowledgeBaseIds, String query, int topK);

    IndexVerification verifyVersion(IndexLayer layer, long tenantId, long documentId, long versionId);

    void deleteVersion(IndexLayer layer, long tenantId, long documentId, long versionId);
}

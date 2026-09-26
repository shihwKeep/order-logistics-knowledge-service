package com.xjjk.knowledge.retrieval.index;

import com.xjjk.knowledge.retrieval.model.IndexChunk;
import com.xjjk.knowledge.retrieval.model.IndexLayer;
import com.xjjk.knowledge.retrieval.model.RecallCandidate;
import com.xjjk.knowledge.retrieval.model.DocumentVersionRef;

import java.util.List;

public interface KeywordIndex {
    void ensureReady();

    void replaceVersion(IndexLayer layer, List<IndexChunk> chunks);

    List<RecallCandidate> search(
            IndexLayer layer, long tenantId, List<Long> knowledgeBaseIds, String query, int topK);

    default List<RecallCandidate> search(
            IndexLayer layer,
            long tenantId,
            List<Long> knowledgeBaseIds,
            List<DocumentVersionRef> allowedVersions,
            String query,
            int topK) {
        return search(layer, tenantId, knowledgeBaseIds, query, topK);
    }

    IndexVerification verifyVersion(IndexLayer layer, long tenantId, long documentId, long versionId);

    void deleteVersion(IndexLayer layer, long tenantId, long documentId, long versionId);
}

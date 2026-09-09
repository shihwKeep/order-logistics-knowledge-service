package com.xjjk.knowledge.retrieval.index;

import java.util.Map;

/** 索引中稳定 Chunk ID 到内容哈希的快照，用于发布前一致性校验。 */
public record IndexVerification(Map<String, String> fingerprints) {
    public IndexVerification {
        fingerprints = Map.copyOf(fingerprints);
    }

    public int count() {
        return fingerprints.size();
    }
}

package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.retrieval.model.DocumentVersionRef;

import java.util.List;
import java.util.Map;

/** 一次线上检索所使用的 ACTIVE Release 不可变快照。 */
public record ActiveReleaseScope(
        Map<Long, Long> releaseIds,
        List<DocumentVersionRef> versions) {

    public ActiveReleaseScope {
        releaseIds = releaseIds == null ? Map.of() : Map.copyOf(releaseIds);
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    public boolean isEmpty() {
        return releaseIds.isEmpty() || versions.isEmpty();
    }
}

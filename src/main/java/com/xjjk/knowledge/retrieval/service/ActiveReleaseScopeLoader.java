package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.retrieval.model.DocumentVersionRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从MySQL事实源一次性加载线上Release指针及其完整文档版本清单。 */
@Component
public class ActiveReleaseScopeLoader {
    private final ActiveReleaseScopeMapper mapper;

    public ActiveReleaseScopeLoader(ActiveReleaseScopeMapper mapper) {
        this.mapper = mapper;
    }

    public ActiveReleaseScope load(long tenantId, List<Long> knowledgeBaseIds) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("租户ID必须为正数");
        }
        List<Long> requested = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        if (requested.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("知识库ID必须为正数");
        }
        List<ActiveReleaseScopeMapper.ScopeRow> rows = mapper.findActiveScope(tenantId, requested);
        Map<Long, Long> releaseIds = new LinkedHashMap<>();
        Set<DocumentVersionRef> uniqueVersions = new LinkedHashSet<>();
        for (ActiveReleaseScopeMapper.ScopeRow row : rows) {
            Long previous = releaseIds.putIfAbsent(row.knowledgeBaseId(), row.releaseId());
            if (previous != null && previous.longValue() != row.releaseId()) {
                throw new IllegalStateException("同一知识库返回了多个ACTIVE Release");
            }
            uniqueVersions.add(new DocumentVersionRef(
                    row.knowledgeBaseId(), row.documentId(), row.versionId()));
        }
        return new ActiveReleaseScope(releaseIds, new ArrayList<>(uniqueVersions));
    }
}

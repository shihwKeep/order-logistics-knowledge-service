package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 管理台草稿检索终审，只允许当前草稿指针指向的版本。 */
@Component
public class DraftVersionValidator {
    private final DraftVersionMapper mapper;

    public DraftVersionValidator(DraftVersionMapper mapper) {
        this.mapper = mapper;
    }

    public List<RankedEvidence> validate(long tenantId, List<RankedEvidence> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Map<String, VersionReference> unique = new LinkedHashMap<>();
        candidates.stream().filter(candidate -> candidate.chunk().tenantId() == tenantId).forEach(candidate -> {
            VersionReference reference = new VersionReference(
                    candidate.chunk().documentId(), candidate.chunk().versionId());
            unique.putIfAbsent(reference.key(), reference);
        });
        if (unique.isEmpty()) return List.of();
        Set<String> valid = Set.copyOf(mapper.findDraftKeys(tenantId, List.copyOf(unique.values())));
        return candidates.stream()
                .filter(candidate -> candidate.chunk().tenantId() == tenantId)
                .filter(candidate -> valid.contains(
                        candidate.chunk().documentId() + ":" + candidate.chunk().versionId()))
                .toList();
    }
}

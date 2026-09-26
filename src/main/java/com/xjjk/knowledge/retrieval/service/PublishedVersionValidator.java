package com.xjjk.knowledge.retrieval.service;

import com.xjjk.knowledge.retrieval.model.RankedEvidence;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 返回证据前以 MySQL 当前 ACTIVE Release 清单进行最后一次批量校验，
 * 阻断预写版本、旧 Release 和冲突 Release 的索引数据泄漏到线上回答。
 */
@Component
public class PublishedVersionValidator {
    private final PublishedVersionMapper mapper;

    public PublishedVersionValidator(PublishedVersionMapper mapper) {
        this.mapper = mapper;
    }

    public List<RankedEvidence> validate(long tenantId, List<RankedEvidence> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, VersionReference> unique = new LinkedHashMap<>();
        for (RankedEvidence candidate : candidates) {
            if (candidate.chunk().tenantId() != tenantId) {
                continue;
            }
            VersionReference reference = new VersionReference(
                    candidate.chunk().documentId(), candidate.chunk().versionId());
            unique.putIfAbsent(reference.key(), reference);
        }
        if (unique.isEmpty()) {
            return List.of();
        }
        Set<String> valid = Set.copyOf(mapper.findPublishedKeys(tenantId, List.copyOf(unique.values())));
        return candidates.stream()
                .filter(candidate -> candidate.chunk().tenantId() == tenantId)
                .filter(candidate -> valid.contains(
                        candidate.chunk().documentId() + ":" + candidate.chunk().versionId()))
                .toList();
    }
}

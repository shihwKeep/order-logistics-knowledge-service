package com.xjjk.knowledge.knowledgebase.persistence;

import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBase;
import com.xjjk.knowledge.knowledgebase.domain.KnowledgeBaseStatus;

import java.util.List;
import java.util.Optional;

/** 租户安全的知识库存储契约。 */
public interface KnowledgeBaseRepository {

    KnowledgeBase create(long tenantId, long actorUserId, String name, String description);

    Optional<KnowledgeBase> findById(long tenantId, long knowledgeBaseId);

    List<KnowledgeBase> list(long tenantId);

    KnowledgeBase update(
            long tenantId,
            long knowledgeBaseId,
            int expectedVersion,
            String name,
            String description,
            long actorUserId);

    KnowledgeBase setStatus(
            long tenantId,
            long knowledgeBaseId,
            KnowledgeBaseStatus status,
            long actorUserId);

    void softDelete(long tenantId, long knowledgeBaseId, long actorUserId);
}

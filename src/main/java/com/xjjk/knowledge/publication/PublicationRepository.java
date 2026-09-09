package com.xjjk.knowledge.publication;

import java.util.Optional;

public interface PublicationRepository {
    Optional<PublicationRecord> findByRequest(long tenantId, String requestId);
    PublicationTarget loadVersionTarget(long tenantId, long knowledgeBaseId, long documentId, long versionId);
    PublicationTarget loadCurrentPublishedTarget(long tenantId, long knowledgeBaseId, long documentId);
    PublicationRecord activate(
            PublicationTarget expected, PublicationAction action, long actorUserId, String requestId);
    PublicationRecord disable(PublicationTarget expected, long actorUserId, String requestId);
}

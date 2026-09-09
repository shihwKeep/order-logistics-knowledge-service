package com.xjjk.knowledge.document.persistence;

import com.xjjk.knowledge.document.domain.CreatedDocument;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.domain.KnowledgeDocument;
import com.xjjk.knowledge.document.domain.SourceFile;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository {
    CreatedDocument createDraft(long tenantId, long knowledgeBaseId, long actorUserId,
                                String title, SourceFile source);

    Optional<KnowledgeDocument> findDocument(long tenantId, long documentId);

    Optional<DocumentVersion> findVersion(long tenantId, long documentId, long versionId);

    List<KnowledgeDocument> listDocuments(long tenantId, long knowledgeBaseId);

    List<DocumentVersion> listVersions(long tenantId, long documentId);
}

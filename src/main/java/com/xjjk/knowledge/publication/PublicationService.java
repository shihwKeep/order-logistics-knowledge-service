package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.publication.release.KnowledgeRelease;
import com.xjjk.knowledge.publication.release.ReleaseService;
import org.springframework.stereotype.Service;

/**
 * 旧文档级发布接口的兼容适配器。
 * 所有操作只创建知识库级 Release，不再直接写 ES/Milvus 或修改线上文档指针。
 */
@Service
public class PublicationService {
    private final ReleaseService releases;

    public PublicationService(ReleaseService releases) {
        this.releases = releases;
    }

    public KnowledgeRelease publish(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId,
            long documentId, long versionId, String requestId) {
        return releases.publishDocument(
                principal, tenantId, knowledgeBaseId, documentId, versionId, requestId);
    }

    public KnowledgeRelease rollback(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId,
            long documentId, long versionId, String requestId) {
        return releases.rollbackDocument(
                principal, tenantId, knowledgeBaseId, documentId, versionId, requestId);
    }

    public KnowledgeRelease disable(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId,
            long documentId, String requestId) {
        return releases.disableDocument(
                principal, tenantId, knowledgeBaseId, documentId, requestId);
    }
}

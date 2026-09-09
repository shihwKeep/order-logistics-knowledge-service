package com.xjjk.knowledge.publication;

import com.xjjk.knowledge.audit.AuditAction;
import com.xjjk.knowledge.audit.AuditService;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.document.domain.DocumentStatus;
import com.xjjk.knowledge.retrieval.indexing.PublicationIndexService;
import com.xjjk.knowledge.tenant.TenantAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.Map;
import java.util.Optional;

/** 手动发布入口：先准备外部双索引，再用短事务切换 MySQL 发布指针。 */
@Service
public class PublicationService {
    private final TenantAccessGuard tenants;
    private final PublicationRepository repository;
    private final PublicationIndexService indexes;
    private final AuditService audit;

    public PublicationService(
            TenantAccessGuard tenants, PublicationRepository repository,
            PublicationIndexService indexes, AuditService audit) {
        this.tenants = tenants;
        this.repository = repository;
        this.indexes = indexes;
        this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PublicationRecord publish(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId,
            String requestId) {
        return activate(principal, tenantId, knowledgeBaseId, documentId, versionId,
                requestId, PublicationAction.PUBLISH);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PublicationRecord rollback(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId,
            String requestId) {
        return activate(principal, tenantId, knowledgeBaseId, documentId, versionId,
                requestId, PublicationAction.ROLLBACK);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PublicationRecord disable(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, String requestId) {
        require(principal, tenantId, requestId);
        Optional<PublicationRecord> duplicate = repository.findByRequest(tenantId, requestId);
        if (duplicate.isPresent()) {
            return requireMatchingDuplicate(
                    duplicate.get(), PublicationAction.DISABLE, knowledgeBaseId, documentId, null);
        }
        PublicationTarget target;
        try {
            // 此调用会锁住文档行。并发的首个停用请求可能在我们等待锁期间已经清空发布指针，
            // 此时仓储会抛出冲突，但事务仍持有刚取得的文档锁，下面必须先做幂等重读。
            target = repository.loadCurrentPublishedTarget(tenantId, knowledgeBaseId, documentId);
        } catch (BusinessException conflictAfterLock) {
            duplicate = repository.findByRequestForUpdate(tenantId, requestId);
            if (duplicate.isPresent()) {
                return requireMatchingDuplicate(
                        duplicate.get(), PublicationAction.DISABLE, knowledgeBaseId, documentId, null);
            }
            throw conflictAfterLock;
        }
        // 发布指针尚未被清空时也要锁后重读，覆盖另一请求已提交但当前事务首次读取未看到的情况。
        duplicate = repository.findByRequestForUpdate(tenantId, requestId);
        if (duplicate.isPresent()) {
            return requireMatchingDuplicate(
                    duplicate.get(), PublicationAction.DISABLE, knowledgeBaseId, documentId, null);
        }
        PublicationRecord record = repository.disable(target, principal.userId(), requestId);
        audit.success(tenantId, principal, AuditAction.DOCUMENT_DISABLE, "DOCUMENT", Long.toString(documentId),
                requestId, Map.of("versionId", target.version().id()));
        return record;
    }

    private PublicationRecord activate(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long documentId, long versionId,
            String requestId, PublicationAction action) {
        require(principal, tenantId, requestId);
        Optional<PublicationRecord> duplicate = repository.findByRequest(tenantId, requestId);
        if (duplicate.isPresent()) {
            return requireMatchingDuplicate(
                    duplicate.get(), action, knowledgeBaseId, documentId, versionId);
        }
        PublicationTarget target = repository.loadVersionTarget(tenantId, knowledgeBaseId, documentId, versionId);
        // 首次查询与取得文档行锁之间可能已有并发请求完成；锁后再读一次即可返回同一结果。
        duplicate = repository.findByRequestForUpdate(tenantId, requestId);
        if (duplicate.isPresent()) {
            return requireMatchingDuplicate(
                    duplicate.get(), action, knowledgeBaseId, documentId, versionId);
        }
        if (action == PublicationAction.PUBLISH
                && (target.version().status() != DocumentStatus.READY
                || !Long.valueOf(versionId).equals(target.document().currentDraftVersionId()))) {
            throw new BusinessException(ApiErrorCode.DOCUMENT_NOT_READY);
        }
        if (action == PublicationAction.ROLLBACK
                && target.version().status() != DocumentStatus.READY
                && target.version().status() != DocumentStatus.PUBLISHED
                && target.version().status() != DocumentStatus.ARCHIVED) {
            throw new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT);
        }
        if (action == PublicationAction.ROLLBACK
                && Long.valueOf(versionId).equals(target.document().currentPublishedVersionId())) {
            // 对当前线上版本执行 delete→rewrite 会在任一外部步骤失败时破坏可用性，明确拒绝无效回滚。
            throw new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT,
                    "目标版本已经是当前发布版本");
        }
        indexes.preparePublished(target.version());
        PublicationRecord record = repository.activate(target, action, principal.userId(), requestId);
        audit.success(tenantId, principal,
                action == PublicationAction.PUBLISH ? AuditAction.DOCUMENT_PUBLISH : AuditAction.DOCUMENT_ROLLBACK,
                "DOCUMENT_VERSION", Long.toString(versionId), requestId, Map.of("versionId", versionId));
        return record;
    }

    private void require(AdminPrincipal principal, long tenantId, String requestId) {
        tenants.requireManage(principal, tenantId);
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
    }

    private PublicationRecord requireMatchingDuplicate(
            PublicationRecord existing,
            PublicationAction action,
            long knowledgeBaseId,
            long documentId,
            Long targetVersionId) {
        if (existing.action() != action
                || existing.knowledgeBaseId() != knowledgeBaseId
                || existing.documentId() != documentId
                || !java.util.Objects.equals(existing.toVersionId(), targetVersionId)) {
            throw new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT,
                    "幂等请求号已用于其他发布动作或目标");
        }
        return existing;
    }
}

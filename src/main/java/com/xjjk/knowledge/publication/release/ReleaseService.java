package com.xjjk.knowledge.publication.release;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.knowledgebase.service.KnowledgeBaseService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReleaseService {
    private final KnowledgeBaseService knowledgeBases;
    private final ReleaseRepository repository;

    public ReleaseService(KnowledgeBaseService knowledgeBases, ReleaseRepository repository) {
        this.knowledgeBases = knowledgeBases;
        this.repository = repository;
    }

    /**
     * 以当前活动 Release 为基线，只替换请求指定的文档版本，然后冻结一份完整清单。
     * MySQL 事务只登记不可变 Release、Item、任务和 Outbox，不在事务内访问 ES/Milvus。
     */
    @Transactional
    public KnowledgeRelease create(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            List<ReleaseReplacement> replacements,
            String requestId) {
        requireKnowledgeBase(principal, tenantId, knowledgeBaseId);
        validateReplacements(replacements, requestId);

        KnowledgeRelease duplicate = repository.findByRequest(tenantId, requestId).orElse(null);
        if (duplicate != null) {
            return requireMatchingReplacementRetry(duplicate, replacements, knowledgeBaseId);
        }

        ReleaseBaseline baseline = requireBaseline(tenantId, knowledgeBaseId);
        duplicate = repository.findByRequest(tenantId, requestId).orElse(null);
        if (duplicate != null) {
            return requireMatchingReplacementRetry(duplicate, replacements, knowledgeBaseId);
        }
        Map<Long, ReleaseItem> manifest = currentManifest(baseline);
        for (ReleaseReplacement replacement : replacements) {
            ReleaseItem current = manifest.get(replacement.documentId());
            if (current != null && current.versionId() == replacement.versionId()) {
                continue;
            }
            ReleaseItem target = repository.findReadyItem(
                            tenantId, knowledgeBaseId,
                            replacement.documentId(), replacement.versionId())
                    .orElseThrow(() -> new BusinessException(ApiErrorCode.DOCUMENT_NOT_READY));
            manifest.put(replacement.documentId(), target);
        }
        return persist(principal, tenantId, knowledgeBaseId, baseline,
                new ArrayList<>(manifest.values()), requestId);
    }

    @Transactional
    public KnowledgeRelease publishDocument(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId,
            long documentId, long versionId, String requestId) {
        return create(principal, tenantId, knowledgeBaseId,
                List.of(new ReleaseReplacement(documentId, versionId)), requestId);
    }

    /** 兼容旧文档回滚入口；允许把 READY、PUBLISHED 或 ARCHIVED 版本放进新清单。 */
    @Transactional
    public KnowledgeRelease rollbackDocument(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId,
            long documentId, long versionId, String requestId) {
        requireKnowledgeBase(principal, tenantId, knowledgeBaseId);
        validateReplacements(List.of(new ReleaseReplacement(documentId, versionId)), requestId);
        KnowledgeRelease duplicate = repository.findByRequest(tenantId, requestId).orElse(null);
        if (duplicate != null) {
            return requireMatchingReplacementRetry(
                    duplicate, List.of(new ReleaseReplacement(documentId, versionId)), knowledgeBaseId);
        }
        ReleaseBaseline baseline = requireBaseline(tenantId, knowledgeBaseId);
        duplicate = repository.findByRequest(tenantId, requestId).orElse(null);
        if (duplicate != null) {
            return requireMatchingReplacementRetry(
                    duplicate, List.of(new ReleaseReplacement(documentId, versionId)), knowledgeBaseId);
        }
        Map<Long, ReleaseItem> manifest = currentManifest(baseline);
        ReleaseItem target = repository.findPublishableItem(
                        tenantId, knowledgeBaseId, documentId, versionId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT));
        manifest.put(documentId, target);
        return persist(principal, tenantId, knowledgeBaseId, baseline,
                new ArrayList<>(manifest.values()), requestId);
    }

    /** 兼容旧停用入口；通过创建“不包含目标文档”的新 Release 完成停用。 */
    @Transactional
    public KnowledgeRelease disableDocument(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId,
            long documentId, String requestId) {
        requireKnowledgeBase(principal, tenantId, knowledgeBaseId);
        validateRequestId(requestId);
        KnowledgeRelease duplicate = repository.findByRequest(tenantId, requestId).orElse(null);
        if (duplicate != null) {
            if (duplicate.knowledgeBaseId() == knowledgeBaseId
                    && repository.items(duplicate.id()).stream()
                    .noneMatch(item -> item.documentId() == documentId)) {
                return duplicate;
            }
            throw new BusinessException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        ReleaseBaseline baseline = requireBaseline(tenantId, knowledgeBaseId);
        duplicate = repository.findByRequest(tenantId, requestId).orElse(null);
        if (duplicate != null) {
            return requireMatchingDisabledRetry(duplicate, knowledgeBaseId, documentId);
        }
        Map<Long, ReleaseItem> manifest = currentManifest(baseline);
        if (manifest.remove(documentId) == null) {
            throw new BusinessException(ApiErrorCode.PUBLICATION_CONFLICT,
                    "目标文档当前不在线上 Release 中");
        }
        return persist(principal, tenantId, knowledgeBaseId, baseline,
                new ArrayList<>(manifest.values()), requestId);
    }

    /** 回滚不会修改历史 Release，而是复制其完整清单并创建新的 PREPARING Release。 */
    @Transactional
    public KnowledgeRelease rollback(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId,
            long targetReleaseId, String requestId) {
        requireKnowledgeBase(principal, tenantId, knowledgeBaseId);
        validateRequestId(requestId);
        KnowledgeRelease target = repository.find(tenantId, knowledgeBaseId, targetReleaseId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.RELEASE_NOT_FOUND));
        List<ReleaseItem> targetItems = repository.items(target.id());
        KnowledgeRelease duplicate = repository.findByRequest(tenantId, requestId).orElse(null);
        if (duplicate != null) {
            String targetManifest = manifestSha256(targetItems);
            if (duplicate.knowledgeBaseId() == knowledgeBaseId
                    && duplicate.manifestSha256().equals(targetManifest)) {
                return duplicate;
            }
            throw new BusinessException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        ReleaseBaseline baseline = requireBaseline(tenantId, knowledgeBaseId);
        duplicate = repository.findByRequest(tenantId, requestId).orElse(null);
        if (duplicate != null) {
            String targetManifest = manifestSha256(targetItems);
            if (duplicate.knowledgeBaseId() == knowledgeBaseId
                    && duplicate.manifestSha256().equals(targetManifest)) {
                return duplicate;
            }
            throw new BusinessException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return persist(principal, tenantId, knowledgeBaseId, baseline, targetItems, requestId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeRelease> list(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId) {
        requireKnowledgeBase(principal, tenantId, knowledgeBaseId);
        return repository.list(tenantId, knowledgeBaseId);
    }

    @Transactional(readOnly = true)
    public KnowledgeRelease get(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long releaseId) {
        requireKnowledgeBase(principal, tenantId, knowledgeBaseId);
        return repository.find(tenantId, knowledgeBaseId, releaseId)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.RELEASE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<ReleaseItem> items(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId, long releaseId) {
        get(principal, tenantId, knowledgeBaseId, releaseId);
        return repository.items(releaseId);
    }

    private KnowledgeRelease persist(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            ReleaseBaseline baseline,
            List<ReleaseItem> items,
            String requestId) {
        items = items.stream()
                .sorted(Comparator.comparingLong(ReleaseItem::documentId))
                .toList();
        String manifestSha256 = manifestSha256(items);
        if ((baseline.currentReleaseId() == null && items.isEmpty())
                || manifestSha256.equals(baseline.currentManifestSha256())) {
            throw new BusinessException(ApiErrorCode.RELEASE_NO_CHANGES);
        }
        KnowledgeRelease release = repository.insert(KnowledgeRelease.preparing(
                tenantId, knowledgeBaseId,
                repository.nextReleaseNumber(tenantId, knowledgeBaseId),
                baseline.currentReleaseId(), requestId, manifestSha256,
                baseline.rowVersion(), principal.userId()));
        repository.insertItems(release.id(), items);
        repository.enqueue(release);
        return release;
    }

    private Map<Long, ReleaseItem> currentManifest(ReleaseBaseline baseline) {
        Map<Long, ReleaseItem> manifest = new LinkedHashMap<>();
        if (baseline.currentReleaseId() != null) {
            repository.items(baseline.currentReleaseId())
                    .forEach(item -> manifest.put(item.documentId(), item));
        }
        return manifest;
    }

    private ReleaseBaseline requireBaseline(long tenantId, long knowledgeBaseId) {
        ReleaseBaseline baseline = repository.lockBaseline(tenantId, knowledgeBaseId);
        if (baseline == null) {
            throw new BusinessException(ApiErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return baseline;
    }

    private KnowledgeRelease requireMatchingReplacementRetry(
            KnowledgeRelease existing,
            List<ReleaseReplacement> replacements,
            long knowledgeBaseId) {
        Map<Long, Long> existingVersions = repository.items(existing.id()).stream()
                .collect(Collectors.toMap(ReleaseItem::documentId, ReleaseItem::versionId));
        boolean matches = existing.knowledgeBaseId() == knowledgeBaseId
                && replacements.stream().allMatch(replacement ->
                Long.valueOf(replacement.versionId())
                        .equals(existingVersions.get(replacement.documentId())));
        if (!matches) {
            throw new BusinessException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return existing;
    }

    private KnowledgeRelease requireMatchingDisabledRetry(
            KnowledgeRelease existing, long knowledgeBaseId, long documentId) {
        if (existing.knowledgeBaseId() == knowledgeBaseId
                && repository.items(existing.id()).stream()
                .noneMatch(item -> item.documentId() == documentId)) {
            return existing;
        }
        throw new BusinessException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    private void requireKnowledgeBase(
            AdminPrincipal principal, long tenantId, long knowledgeBaseId) {
        knowledgeBases.get(principal, tenantId, knowledgeBaseId);
    }

    static String manifestSha256(List<ReleaseItem> items) {
        String canonical = items.stream()
                .sorted(Comparator.comparingLong(ReleaseItem::documentId))
                .map(item -> item.documentId() + ":" + item.versionId()
                        + ":" + item.contentManifestSha256())
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private void validateReplacements(
            List<ReleaseReplacement> replacements, String requestId) {
        validateRequestId(requestId);
        if (replacements == null || replacements.isEmpty()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
        long distinctDocuments = replacements.stream()
                .map(ReleaseReplacement::documentId)
                .distinct()
                .count();
        if (distinctDocuments != replacements.size()
                || replacements.stream().anyMatch(item ->
                item.documentId() <= 0 || item.versionId() <= 0)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
    }
}

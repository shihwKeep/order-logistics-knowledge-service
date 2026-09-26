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
     * 在一个短事务中冻结完整清单并登记异步任务；不在事务内写 ES/Milvus。
     * 知识库行锁既串行化 release_number，也固定本次发布的活动 Release 与 row_version 基线。
     */
    @Transactional
    public KnowledgeRelease create(
            AdminPrincipal principal,
            long tenantId,
            long knowledgeBaseId,
            List<ReleaseReplacement> replacements,
            String requestId) {
        knowledgeBases.get(principal, tenantId, knowledgeBaseId);
        validateRequest(replacements, requestId);
        ReleaseBaseline baseline = repository.lockBaseline(tenantId, knowledgeBaseId);
        if (baseline == null) {
            throw new BusinessException(ApiErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        Map<Long, ReleaseItem> manifest = new LinkedHashMap<>();
        if (baseline.currentReleaseId() != null) {
            repository.items(baseline.currentReleaseId())
                    .forEach(item -> manifest.put(item.documentId(), item));
        }
        var duplicate = repository.findByRequest(tenantId, requestId);
        Map<Long, ReleaseItem> duplicateManifest = new LinkedHashMap<>();
        duplicate.ifPresent(existing -> repository.items(existing.id())
                .forEach(item -> duplicateManifest.put(item.documentId(), item)));
        for (ReleaseReplacement replacement : replacements) {
            ReleaseItem current = manifest.get(replacement.documentId());
            if (current != null && current.versionId() == replacement.versionId()) {
                continue;
            }
            ReleaseItem previousRequestTarget = duplicateManifest.get(replacement.documentId());
            ReleaseItem target = previousRequestTarget != null
                    && previousRequestTarget.versionId() == replacement.versionId()
                    ? previousRequestTarget
                    : repository.findReadyItem(
                                    tenantId, knowledgeBaseId,
                                    replacement.documentId(), replacement.versionId())
                            .orElseThrow(() -> new BusinessException(ApiErrorCode.DOCUMENT_NOT_READY));
            manifest.put(replacement.documentId(), target);
        }
        List<ReleaseItem> items = new ArrayList<>(manifest.values());
        items.sort(Comparator.comparingLong(ReleaseItem::documentId));
        String manifestSha256 = manifestSha256(items);

        if (duplicate.isPresent()) {
            KnowledgeRelease existing = duplicate.get();
            if (existing.knowledgeBaseId() == knowledgeBaseId
                    && existing.manifestSha256().equals(manifestSha256)) {
                return existing;
            }
            throw new BusinessException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
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

    private void validateRequest(List<ReleaseReplacement> replacements, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64
                || replacements == null || replacements.isEmpty()) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
        long distinctDocuments = replacements.stream()
                .map(ReleaseReplacement::documentId)
                .distinct()
                .count();
        if (distinctDocuments != replacements.size()
                || replacements.stream().anyMatch(item -> item.documentId() <= 0 || item.versionId() <= 0)) {
            throw new BusinessException(ApiErrorCode.VALIDATION_FAILED);
        }
    }
}

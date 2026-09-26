package com.xjjk.knowledge.document.task;

import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.cloud.budget.CloudModelBudgetExceededException;
import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.document.parser.DocumentParser;
import com.xjjk.knowledge.document.parser.DocumentParserRegistry;
import com.xjjk.knowledge.document.parser.ParseRequest;
import com.xjjk.knowledge.document.parser.ParsedDocument;
import com.xjjk.knowledge.document.persistence.DocumentRepository;
import com.xjjk.knowledge.document.processing.DocumentChunk;
import com.xjjk.knowledge.document.processing.StructuralChunker;
import com.xjjk.knowledge.document.storage.SourceObjectStore;
import com.xjjk.knowledge.retrieval.indexing.DraftIndexingService;
import com.xjjk.knowledge.retrieval.indexing.IngestionLeaseLostException;
import com.xjjk.knowledge.observation.KnowledgeMetrics;
import java.io.InputStream;
import java.util.List;
import org.springframework.stereotype.Component;

/** 单个任务的完整处理边界；先持有数据库租约，再读取原件并幂等替换派生数据。 */
@Component
public class IngestionWorker {
    private final IngestionTaskRepository tasks;
    private final DocumentRepository documents;
    private final SourceObjectStore objectStore;
    private final DocumentParserRegistry parsers;
    private final IngestionArtifactRepository artifacts;
    private final StructuralChunker chunker;
    private final DraftIndexingService indexing;
    private final IngestionProperties properties;
    private final IngestionBulkhead bulkhead;
    private final KnowledgeMetrics metrics;

    public IngestionWorker(
            IngestionTaskRepository tasks,
            DocumentRepository documents,
            SourceObjectStore objectStore,
            DocumentParserRegistry parsers,
            IngestionArtifactRepository artifacts,
            StructuralChunker chunker,
            DraftIndexingService indexing,
            IngestionProperties properties,
            IngestionBulkhead bulkhead,
            KnowledgeMetrics metrics) {
        this.tasks = tasks;
        this.documents = documents;
        this.objectStore = objectStore;
        this.parsers = parsers;
        this.artifacts = artifacts;
        this.chunker = chunker;
        this.indexing = indexing;
        this.properties = properties;
        this.bulkhead = bulkhead;
        this.metrics = metrics;
    }

    public boolean process(long taskId) {
        long startedAt = System.nanoTime();
        if (!bulkhead.tryAcquire()) {
            metrics.recordIngestionRejected();
            return false;
        }
        String stage = "CLAIM";
        String outcome = "FAILED";
        try {
            var claimed = tasks.claim(
                    taskId, properties.getWorkerId(), properties.getLeaseDuration());
            if (claimed.isEmpty()) {
                outcome = "UNCLAIMED";
                return false;
            }
            stage = claimed.get().stage();
            boolean completed = processClaimed(claimed.get());
            outcome = completed ? "SUCCESS" : "FAILED";
            return completed;
        } finally {
            // 指标与许可都由最外层 finally 收口，数据库、解析器和索引异常均不会泄漏许可。
            bulkhead.release();
            metrics.recordIngestion(stage, outcome, elapsed(startedAt));
        }
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private boolean processClaimed(IngestionTaskLease lease) {
        DocumentVersion version = null;
        try {
            version = documents.findVersion(
                            lease.tenantId(), lease.documentId(), lease.versionId())
                    .orElseThrow(() -> new IllegalStateException("任务对应的文档版本不存在"));
            if ("INDEX".equals(lease.stage())) {
                try (IngestionLeaseHeartbeat heartbeat = new IngestionLeaseHeartbeat(
                        tasks, lease, properties.getLeaseDuration())) {
                    if (!heartbeat.start()) {
                        return false;
                    }
                    if (!artifacts.prepareIndexAttempt(version, lease)) {
                        // 当前修订已被替换，或租约已转移；旧任务不再重试。
                        tasks.complete(lease.taskId(), lease.leaseToken());
                        return false;
                    }
                    indexing.index(
                            version, heartbeat::isHeld, lease.taskId(), lease.leaseToken());
                    return heartbeat.isHeld()
                            && tasks.complete(lease.taskId(), lease.leaseToken());
                }
            }
            if ("CHUNK".equals(lease.stage())) {
                ParsedDocument parsed = artifacts.loadEffectiveUnits(version);
                List<DocumentChunk> chunks = chunker.chunk(
                        version.tenantId(), version.documentId(), version.id(), parsed.units());
                artifacts.replaceChunks(version, chunks);
                return tasks.complete(lease.taskId(), lease.leaseToken());
            }
            byte[] content;
            try (InputStream source = objectStore.get(version.sourceObjectKey())) {
                content = source.readAllBytes();
            }
            DocumentParser parser = parsers.select(version.fileExtension(), version.mimeType());
            ParsedDocument parsed = parser.parse(new ParseRequest(
                    version.originalFilename(), version.fileExtension(), version.mimeType(), content));
            List<DocumentChunk> chunks = chunker.chunk(
                    version.tenantId(), version.documentId(), version.id(), parsed.units());
            artifacts.replaceParsedArtifacts(version, parsed, chunks, parser.version());
            return tasks.complete(lease.taskId(), lease.leaseToken());
        } catch (IngestionLeaseLostException exception) {
            // 租约已转移给其他 Worker，旧 Worker 不能覆盖版本失败状态或新任务结果。
            tasks.complete(lease.taskId(), lease.leaseToken());
            return false;
        } catch (CloudModelBudgetExceededException exception) {
            // 月度额度会自动恢复；保留既有 Chunk 和版本状态，不消耗有限的普通重试次数。
            tasks.defer(
                    lease.taskId(), lease.leaseToken(), "KNOWLEDGE_MODEL_BUDGET_EXHAUSTED",
                    exception.getMessage(), properties.getBudgetRetryDelay());
            return false;
        } catch (Exception exception) {
            String code = exception instanceof BusinessException business
                    ? business.errorCode().code() : "DOCUMENT_INGESTION_FAILED";
            boolean canRetry = version == null
                    || artifacts.markFailedIfOwned(version, lease, lease.stage(), code);
            if (canRetry) {
                boolean failed = tasks.fail(
                        lease.taskId(), lease.leaseToken(), code, exception.getMessage(),
                        properties.getMaxRetries(), properties.getRetryBaseDelay());
                if (failed && version != null) {
                    // Mapper 内部仅在任务已进入 DEAD 时登记；普通 RETRY 不会提前删除可复用索引。
                    artifacts.registerFinalFailureCleanup(version, lease);
                }
            } else {
                // 同一版本已进入更新修订或终态，当前任务已过期，直接结束其租约。
                tasks.complete(lease.taskId(), lease.leaseToken());
            }
            return false;
        }
    }
}

package com.xjjk.knowledge.publication.cleanup;

import com.xjjk.knowledge.document.storage.DocumentStorageProperties;
import com.xjjk.knowledge.document.storage.SourceObjectStore;
import com.xjjk.knowledge.document.task.IngestionArtifactRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 清理由“MinIO 写入成功、MySQL 事务提交失败”产生的极少量孤儿原文件。
 * 24 小时安全窗、每轮上限和数据库引用复核共同避免误删正在上传或已登记的原件。
 */
@Component
@ConditionalOnProperty(
        prefix = "knowledge.document.storage", name = "orphan-scan-enabled", havingValue = "true")
public class OrphanSourceObjectScanner {
    private static final Logger log = LoggerFactory.getLogger(OrphanSourceObjectScanner.class);

    private final SourceObjectStore store;
    private final IngestionArtifactRepository artifacts;
    private final DocumentStorageProperties properties;
    private final Clock clock;

    @Autowired
    public OrphanSourceObjectScanner(
            SourceObjectStore store,
            IngestionArtifactRepository artifacts,
            DocumentStorageProperties properties) {
        this(store, artifacts, properties, Clock.systemUTC());
    }

    OrphanSourceObjectScanner(
            SourceObjectStore store,
            IngestionArtifactRepository artifacts,
            DocumentStorageProperties properties,
            Clock clock) {
        this.store = store;
        this.artifacts = artifacts;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${knowledge.document.storage.orphan-scan-delay:1h}")
    public void scan() {
        Instant cutoff = clock.instant().minus(properties.getOrphanSafetyWindow());
        store.listSourceObjectsOlderThan(cutoff, properties.getOrphanScanBatchSize())
                .stream()
                .filter(object -> object.lastModified().isBefore(cutoff))
                .forEach(object -> {
                    try {
                        if (!artifacts.isSourceObjectReferenced(object.key())) {
                            store.delete(object.key());
                        }
                    } catch (RuntimeException exception) {
                        // 单个对象失败不能阻断本轮其余对象；下轮扫描会再次尝试。
                        log.warn("knowledge_orphan_source_cleanup_failed objectKey={}, exceptionType={}",
                                object.key(), exception.getClass().getSimpleName());
                    }
                });
    }
}

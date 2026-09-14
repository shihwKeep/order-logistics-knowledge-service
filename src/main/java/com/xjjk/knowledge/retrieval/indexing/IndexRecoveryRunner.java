package com.xjjk.knowledge.retrieval.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

/** 仅在维护命令显式开启时执行；日常启动默认不会全量重算向量。 */
@Component
@Order(1)
@ConditionalOnProperty(
        prefix = "knowledge.maintenance",
        name = "rebuild-indexes-on-startup",
        havingValue = "true")
public class IndexRecoveryRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(IndexRecoveryRunner.class);

    private final IndexRecoveryService recovery;

    public IndexRecoveryRunner(IndexRecoveryService recovery) {
        this.recovery = recovery;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.warn("knowledge_index_recovery started=true");
        IndexRecoveryResult result = recovery.rebuildAll();
        log.info("knowledge_index_recovery completed=true, draftVersions={}, publishedVersions={}",
                result.draftVersions(), result.publishedVersions());
    }
}

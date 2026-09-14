package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.retrieval.model.IndexLayer;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 灾备全量索引恢复器。逐个版本完成 ES 与 Milvus 的替换和指纹校验，任一版本失败即停止，
 * 从而避免运维人员把部分恢复误认为完整恢复。
 */
@Service
public class IndexRecoveryService {
    private final IndexRecoveryRepository repository;
    private final PublicationIndexService indexes;

    public IndexRecoveryService(
            IndexRecoveryRepository repository,
            PublicationIndexService indexes) {
        this.repository = repository;
        this.indexes = indexes;
    }

    public IndexRecoveryResult rebuildAll() {
        int drafts = 0;
        int published = 0;
        Map<Long, com.xjjk.knowledge.document.domain.DocumentVersion> migratedVersions =
                new LinkedHashMap<>();
        for (IndexRecoveryTarget target : repository.listCurrentTargets()) {
            if (target.layer() == IndexLayer.PUBLISHED) {
                indexes.recoverPublished(target.version());
                published++;
            } else if (target.layer() == IndexLayer.DRAFT) {
                indexes.recoverDraft(target.version());
                drafts++;
            } else {
                throw new IllegalStateException("不支持恢复索引层: " + target.layer());
            }
            migratedVersions.putIfAbsent(target.version().id(), target.version());
        }
        for (var version : migratedVersions.values()) {
            if (!repository.upgradeEmbeddingContract(version)) {
                throw new IllegalStateException("索引已重建但 Embedding 契约并发更新失败: " + version.id());
            }
        }
        return new IndexRecoveryResult(drafts, published);
    }
}

package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.retrieval.model.IndexLayer;
import org.springframework.stereotype.Service;

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
        for (IndexRecoveryTarget target : repository.listCurrentTargets()) {
            if (target.layer() == IndexLayer.PUBLISHED) {
                indexes.preparePublished(target.version());
                published++;
            } else if (target.layer() == IndexLayer.DRAFT) {
                indexes.prepareDraft(target.version());
                drafts++;
            } else {
                throw new IllegalStateException("不支持恢复索引层: " + target.layer());
            }
        }
        return new IndexRecoveryResult(drafts, published);
    }
}

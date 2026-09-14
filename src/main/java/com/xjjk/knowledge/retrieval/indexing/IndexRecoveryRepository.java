package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.domain.DocumentVersion;

import java.util.List;

/** 读取 MySQL 真相中的当前草稿和当前发布版本，绝不从 ES/Milvus 反推业务状态。 */
public interface IndexRecoveryRepository {
    List<IndexRecoveryTarget> listCurrentTargets();

    /** 新派生索引全部校验成功后，以旧契约为条件原子升级 MySQL 元数据。 */
    boolean upgradeEmbeddingContract(DocumentVersion expectedVersion);
}

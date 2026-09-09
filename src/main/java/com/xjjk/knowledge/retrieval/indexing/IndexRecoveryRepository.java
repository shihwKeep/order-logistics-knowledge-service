package com.xjjk.knowledge.retrieval.indexing;

import java.util.List;

/** 读取 MySQL 真相中的当前草稿和当前发布版本，绝不从 ES/Milvus 反推业务状态。 */
public interface IndexRecoveryRepository {
    List<IndexRecoveryTarget> listCurrentTargets();
}

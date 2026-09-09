package com.xjjk.knowledge.retrieval.indexing;

import com.xjjk.knowledge.document.domain.DocumentVersion;
import com.xjjk.knowledge.retrieval.model.IndexLayer;

/** MySQL 当前指针指向、需要在灾备环境重建的索引层与版本。 */
public record IndexRecoveryTarget(IndexLayer layer, DocumentVersion version) {
}

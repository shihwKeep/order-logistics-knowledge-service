package com.xjjk.knowledge.retrieval.indexing;

/** 一次全量索引恢复实际完成的草稿层和发布层版本数。 */
public record IndexRecoveryResult(int draftVersions, int publishedVersions) {
}

package com.xjjk.knowledge.retrieval.model;

/** 草稿与线上索引必须物理隔离，避免未发布内容进入坐席问答。 */
public enum IndexLayer {
    DRAFT,
    PUBLISHED
}

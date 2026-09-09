package com.xjjk.knowledge.document.domain;

/** 文档版本处理状态；线上只允许读取已经发布的版本。 */
public enum DocumentStatus {
    UPLOADED,
    PARSING,
    OCR_PROCESSING,
    CHUNKING,
    INDEXING,
    READY,
    PUBLISHED,
    ARCHIVED,
    FAILED
}

package com.xjjk.knowledge.retrieval.model;

/** 在线检索使用的降级路径；返回给调用方并写入无正文检索日志。 */
public enum DegradationMode {
    NONE,
    KEYWORD_ONLY,
    VECTOR_ONLY,
    RERANKER_STRICT_RRF,
    NO_RELIABLE_EVIDENCE,
    ALL_RECALL_UNAVAILABLE
}

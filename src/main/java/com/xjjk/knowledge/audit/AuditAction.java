package com.xjjk.knowledge.audit;

/** 当前基础阶段需要记录的知识库管理动作。 */
public enum AuditAction {
    KNOWLEDGE_BASE_CREATE,
    KNOWLEDGE_BASE_UPDATE,
    KNOWLEDGE_BASE_ENABLE,
    KNOWLEDGE_BASE_DISABLE,
    KNOWLEDGE_BASE_DELETE
}

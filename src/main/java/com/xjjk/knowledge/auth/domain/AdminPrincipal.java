package com.xjjk.knowledge.auth.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Set;

/** 登录管理台后的非敏感身份信息。 */
public record AdminPrincipal(
        long userId,
        String account,
        String displayName,
        long tenantId,
        Set<KnowledgeRole> roles
) {
    public AdminPrincipal {
        roles = Set.copyOf(roles);
    }

    @JsonIgnore
    public boolean isSuperAdmin() {
        return roles.contains(KnowledgeRole.KNOWLEDGE_SUPER_ADMIN);
    }
}

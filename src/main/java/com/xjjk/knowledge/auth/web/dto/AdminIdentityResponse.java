package com.xjjk.knowledge.auth.web.dto;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;

import java.util.Set;

/** 管理台可见身份，不包含任何 SSPX 或浏览器令牌。 */
public record AdminIdentityResponse(
        long userId,
        String account,
        String displayName,
        long tenantId,
        Set<KnowledgeRole> roles
) {
    public static AdminIdentityResponse from(AdminPrincipal principal) {
        return new AdminIdentityResponse(
                principal.userId(),
                principal.account(),
                principal.displayName(),
                principal.tenantId(),
                principal.roles());
    }
}

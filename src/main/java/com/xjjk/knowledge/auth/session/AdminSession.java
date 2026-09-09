package com.xjjk.knowledge.auth.session;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;

import java.time.Instant;

/**
 * Redis 内部会话。SSPX 令牌只保存在服务端，永远不返回浏览器。
 */
public record AdminSession(
        AdminPrincipal principal,
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        Instant createdAt,
        Instant lastAccessAt,
        Instant rolesVerifiedAt
) {
    public AdminSession withLastAccessAt(Instant instant) {
        return new AdminSession(
                principal,
                accessToken,
                refreshToken,
                accessTokenExpiresAt,
                createdAt,
                instant,
                rolesVerifiedAt);
    }

    public AdminSession withPrincipalAndRolesVerifiedAt(
            AdminPrincipal updatedPrincipal,
            Instant verifiedAt) {
        return new AdminSession(
                updatedPrincipal,
                accessToken,
                refreshToken,
                accessTokenExpiresAt,
                createdAt,
                lastAccessAt,
                verifiedAt);
    }
}

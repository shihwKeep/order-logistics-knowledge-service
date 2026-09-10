package com.xjjk.knowledge.auth.service;

import com.xjjk.knowledge.auth.client.SspxIdentityClient;
import com.xjjk.knowledge.auth.client.SspxOAuthClient;
import com.xjjk.knowledge.auth.client.dto.SspxCurrentUserPayload;
import com.xjjk.knowledge.auth.client.dto.SspxRolePayload;
import com.xjjk.knowledge.auth.client.dto.SspxTokenResponse;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.auth.session.AdminSession;
import com.xjjk.knowledge.auth.session.AdminSessionRepository;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 管理员登录编排：必须先完成 SSPX 身份和知识库角色验证，最后才创建服务端会话。
 */
@Service
public class AdminLoginService {

    private final SspxOAuthClient oauthClient;
    private final SspxIdentityClient identityClient;
    private final AdminSessionRepository sessionRepository;
    private final Clock clock;

    @Autowired
    public AdminLoginService(
            SspxOAuthClient oauthClient,
            SspxIdentityClient identityClient,
            AdminSessionRepository sessionRepository) {
        this(oauthClient, identityClient, sessionRepository, Clock.systemUTC());
    }

    public AdminLoginService(
            SspxOAuthClient oauthClient,
            SspxIdentityClient identityClient,
            AdminSessionRepository sessionRepository,
            Clock clock) {
        this.oauthClient = oauthClient;
        this.identityClient = identityClient;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    public LoginResult login(String account, String password) {
        SspxTokenResponse token = oauthClient.passwordGrant(account, password);
        SspxCurrentUserPayload currentUser = identityClient.currentUser(token.accessToken());
        List<SspxRolePayload> rolePayloads = identityClient.knowledgeRoles(token.accessToken());
        Set<KnowledgeRole> roles = mapValidRoles(rolePayloads);
        if (roles.isEmpty()) {
            throw new BusinessException(ApiErrorCode.KNOWLEDGE_ACCESS_DENIED);
        }

        AdminPrincipal principal = new AdminPrincipal(
                currentUser.id(),
                currentUser.account(),
                currentUser.name(),
                currentUser.companyId(),
                roles);
        Instant now = clock.instant();
        AdminSession session = new AdminSession(
                principal,
                token.accessToken(),
                token.refreshToken(),
                tokenExpiresAt(token, now),
                now,
                now,
                now);

        // 只有前面的身份与权限校验全部成功后，才会创建浏览器会话。
        String browserToken = sessionRepository.create(session);
        return new LoginResult(browserToken, principal);
    }

    private Set<KnowledgeRole> mapValidRoles(List<SspxRolePayload> rolePayloads) {
        EnumSet<KnowledgeRole> roles = EnumSet.noneOf(KnowledgeRole.class);
        if (rolePayloads == null) {
            return roles;
        }
        for (SspxRolePayload role : rolePayloads) {
            if (role == null
                    || !Integer.valueOf(1).equals(role.status())
                    || Boolean.TRUE.equals(role.isDeleted())) {
                continue;
            }
            try {
                roles.add(KnowledgeRole.valueOf(role.code()));
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // 非知识库角色不参与授权。
            }
        }
        return roles;
    }

    private Instant tokenExpiresAt(SspxTokenResponse token, Instant now) {
        if (token.expiresTime() != null && token.expiresTime() > 0) {
            return Instant.ofEpochMilli(token.expiresTime());
        }
        long expiresIn = token.expiresIn() == null || token.expiresIn() <= 0
                ? 300L
                : token.expiresIn();
        return now.plusSeconds(expiresIn);
    }

    /** 浏览器登录结果只含不透明会话令牌和非敏感身份。 */
    public record LoginResult(String browserToken, AdminPrincipal principal) {
    }
}

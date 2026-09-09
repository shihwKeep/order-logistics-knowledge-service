package com.xjjk.knowledge.auth.service;

import com.xjjk.knowledge.auth.client.SspxIdentityClient;
import com.xjjk.knowledge.auth.client.dto.SspxRolePayload;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.auth.session.AdminSession;
import com.xjjk.knowledge.auth.session.AdminSessionProperties;
import com.xjjk.knowledge.auth.session.AdminSessionRepository;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 定期复核 SSPX 角色，避免角色被停用后旧会话长期保留权限。 */
@Service
public class AdminRoleRefresher {

    private final SspxIdentityClient identityClient;
    private final AdminSessionRepository sessionRepository;
    private final AdminSessionProperties properties;
    private final Clock clock;

    @Autowired
    public AdminRoleRefresher(
            SspxIdentityClient identityClient,
            AdminSessionRepository sessionRepository,
            AdminSessionProperties properties) {
        this(identityClient, sessionRepository, properties, Clock.systemUTC());
    }

    public AdminRoleRefresher(
            SspxIdentityClient identityClient,
            AdminSessionRepository sessionRepository,
            AdminSessionProperties properties,
            Clock clock) {
        this.identityClient = identityClient;
        this.sessionRepository = sessionRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public AdminSession refreshIfRequired(
            String browserToken,
            AdminSession session,
            boolean forceRefresh) {
        Instant now = clock.instant();
        Duration roleAge = Duration.between(session.rolesVerifiedAt(), now);
        if (!forceRefresh && roleAge.compareTo(properties.roleCacheTtl()) < 0) {
            return session;
        }

        List<SspxRolePayload> payloads = identityClient.knowledgeRoles(
                session.accessToken(), session.principal().userId());
        Set<KnowledgeRole> roles = mapValidRoles(payloads);
        if (roles.isEmpty()) {
            // 权限已经被撤销时必须销毁会话，不能只拒绝当前一次请求。
            sessionRepository.delete(browserToken);
            throw new BusinessException(ApiErrorCode.KNOWLEDGE_ACCESS_DENIED);
        }

        AdminPrincipal previous = session.principal();
        AdminPrincipal refreshedPrincipal = new AdminPrincipal(
                previous.userId(),
                previous.account(),
                previous.displayName(),
                previous.tenantId(),
                roles);
        AdminSession refreshed = session.withPrincipalAndRolesVerifiedAt(refreshedPrincipal, now);
        sessionRepository.save(browserToken, refreshed);
        return refreshed;
    }

    private Set<KnowledgeRole> mapValidRoles(List<SspxRolePayload> payloads) {
        EnumSet<KnowledgeRole> roles = EnumSet.noneOf(KnowledgeRole.class);
        if (payloads == null) {
            return roles;
        }
        for (SspxRolePayload role : payloads) {
            if (role == null
                    || !Integer.valueOf(1).equals(role.status())
                    || Boolean.TRUE.equals(role.isDeleted())) {
                continue;
            }
            try {
                roles.add(KnowledgeRole.valueOf(role.code()));
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // 与知识库无关的应用角色不参与授权。
            }
        }
        return roles;
    }
}

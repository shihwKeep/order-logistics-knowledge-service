package com.xjjk.knowledge.auth.service;

import com.xjjk.knowledge.auth.client.SspxIdentityClient;
import com.xjjk.knowledge.auth.client.SspxOAuthClient;
import com.xjjk.knowledge.auth.client.dto.SspxCurrentUserPayload;
import com.xjjk.knowledge.auth.client.dto.SspxRolePayload;
import com.xjjk.knowledge.auth.client.dto.SspxTokenResponse;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.auth.session.AdminSession;
import com.xjjk.knowledge.auth.session.AdminSessionRepository;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminLoginServiceTest {

    private SspxOAuthClient oauthClient;
    private SspxIdentityClient identityClient;
    private AdminSessionRepository sessionRepository;
    private AdminLoginService service;

    @BeforeEach
    void setUp() {
        oauthClient = mock(SspxOAuthClient.class);
        identityClient = mock(SspxIdentityClient.class);
        sessionRepository = mock(AdminSessionRepository.class);
        service = new AdminLoginService(
                oauthClient,
                identityClient,
                sessionRepository,
                Clock.fixed(Instant.parse("2026-09-09T10:00:00Z"), ZoneOffset.UTC));

        when(oauthClient.passwordGrant("74680", "secret"))
                .thenReturn(new SspxTokenResponse(
                        "access", "refresh", "Bearer", 3600L, null, null, null));
        when(identityClient.currentUser("access"))
                .thenReturn(new SspxCurrentUserPayload(
                        10567L, "74680", "石海文", 1061L, 1L));
        when(sessionRepository.create(any(AdminSession.class))).thenReturn("browser-token");
    }

    @Test
    void createsSessionForKnowledgeAdmin() {
        when(identityClient.knowledgeRoles("access"))
                .thenReturn(List.of(role("KNOWLEDGE_ADMIN", 1, false)));

        AdminLoginService.LoginResult result = service.login("74680", "secret");

        assertThat(result.browserToken()).isEqualTo("browser-token");
        assertThat(result.principal().roles()).containsExactly(KnowledgeRole.KNOWLEDGE_ADMIN);
        assertThat(result.principal().tenantId()).isEqualTo(1L);
    }

    @Test
    void supportsSuperAdminAndUserWithBothRoles() {
        when(identityClient.knowledgeRoles("access"))
                .thenReturn(List.of(
                        role("KNOWLEDGE_SUPER_ADMIN", 1, false),
                        role("KNOWLEDGE_ADMIN", 1, false)));

        AdminLoginService.LoginResult result = service.login("74680", "secret");

        assertThat(result.principal().roles()).containsExactlyInAnyOrder(
                KnowledgeRole.KNOWLEDGE_ADMIN,
                KnowledgeRole.KNOWLEDGE_SUPER_ADMIN);
        assertThat(result.principal().isSuperAdmin()).isTrue();
    }

    @Test
    void rejectsOrdinaryDisabledAndDeletedRolesBeforeCreatingSession() {
        when(identityClient.knowledgeRoles("access"))
                .thenReturn(List.of(
                        role("OTHER_ROLE", 1, false),
                        role("KNOWLEDGE_ADMIN", 2, false),
                        role("KNOWLEDGE_SUPER_ADMIN", 1, true)));

        assertThatThrownBy(() -> service.login("74680", "secret"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ApiErrorCode.KNOWLEDGE_ACCESS_DENIED));
        verify(sessionRepository, never()).create(any());
    }

    @Test
    void propagatesInvalidPasswordAndSspxOutageWithoutCreatingSession() {
        when(oauthClient.passwordGrant("bad", "wrong"))
                .thenThrow(new BusinessException(ApiErrorCode.AUTH_INVALID));
        when(oauthClient.passwordGrant("down", "secret"))
                .thenThrow(new BusinessException(ApiErrorCode.AUTH_SERVICE_UNAVAILABLE));

        assertError(() -> service.login("bad", "wrong"), ApiErrorCode.AUTH_INVALID);
        assertError(() -> service.login("down", "secret"), ApiErrorCode.AUTH_SERVICE_UNAVAILABLE);
        verify(sessionRepository, never()).create(any());
    }

    private static SspxRolePayload role(String code, int status, boolean deleted) {
        return new SspxRolePayload(444L, 1L, code, status, deleted);
    }

    private static void assertError(Runnable action, ApiErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}

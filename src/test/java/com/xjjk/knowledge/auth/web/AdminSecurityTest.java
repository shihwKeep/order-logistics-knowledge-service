package com.xjjk.knowledge.auth.web;

import com.xjjk.knowledge.auth.client.SspxIdentityClient;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.auth.service.AdminRoleRefresher;
import com.xjjk.knowledge.auth.session.AdminSession;
import com.xjjk.knowledge.auth.session.AdminSessionProperties;
import com.xjjk.knowledge.auth.session.AdminSessionRepository;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.common.error.BusinessException;
import com.xjjk.knowledge.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(
        controllers = AdminSecurityProbeController.class,
        properties = {
                "spring.config.import=",
                "spring.cloud.nacos.config.enabled=false"
        })
@Import({SecurityConfiguration.class, AdminSessionFilter.class, GlobalExceptionHandler.class})
class AdminSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AdminSessionRepository sessionRepository;

    @MockitoBean
    private AdminRoleRefresher roleRefresher;

    @MockitoBean
    private AdminSessionProperties sessionProperties;

    private AdminSession session;

    @BeforeEach
    void setUp() {
        when(sessionProperties.cookieName()).thenReturn("KB_ADMIN_SESSION");
        when(sessionProperties.roleCacheTtl()).thenReturn(Duration.ofMinutes(5));
        Instant now = Instant.parse("2026-09-09T10:00:00Z");
        session = new AdminSession(
                new AdminPrincipal(
                        10567L, "74680", "石海文", 1L,
                        Set.of(KnowledgeRole.KNOWLEDGE_ADMIN)),
                "access", "refresh", now.plusSeconds(3600), now, now, now);
    }

    @Test
    void missingAndInvalidSessionsReturnUnauthorizedJson() throws Exception {
        mvc.perform(get("/api/v1/admin/security-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        when(sessionRepository.find("invalid")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/admin/security-probe")
                        .cookie(new jakarta.servlet.http.Cookie("KB_ADMIN_SESSION", "invalid")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void authenticatedGetSucceedsButPostWithoutCsrfIsForbidden() throws Exception {
        when(sessionRepository.find("valid")).thenReturn(Optional.of(session));
        when(roleRefresher.refreshIfRequired("valid", session, false)).thenReturn(session);

        mvc.perform(get("/api/v1/admin/security-probe")
                        .cookie(new jakarta.servlet.http.Cookie("KB_ADMIN_SESSION", "valid")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("ok"));

        mvc.perform(post("/api/v1/admin/security-probe")
                        .cookie(new jakarta.servlet.http.Cookie("KB_ADMIN_SESSION", "valid")))
                .andExpect(status().isForbidden());
    }

    @Test
    void signedInternalRouteIsNotBlockedByBrowserSessionOrCsrfRules() throws Exception {
        mvc.perform(post("/api/v1/internal/security-probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("internal-ok"));
    }

    @Test
    void prometheusMetricsRequireSuperAdminSession() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());

        when(sessionRepository.find("admin")).thenReturn(Optional.of(session));
        when(roleRefresher.refreshIfRequired("admin", session, false)).thenReturn(session);
        mvc.perform(get("/actuator/prometheus")
                        .cookie(new jakarta.servlet.http.Cookie("KB_ADMIN_SESSION", "admin")))
                .andExpect(status().isForbidden());

        AdminSession superAdminSession = new AdminSession(
                new AdminPrincipal(
                        10568L, "74681", "超级管理员", 1L,
                        Set.of(KnowledgeRole.KNOWLEDGE_SUPER_ADMIN)),
                "access", "refresh", session.accessTokenExpiresAt(), session.createdAt(),
                session.lastAccessAt(), session.rolesVerifiedAt());
        when(sessionRepository.find("super")).thenReturn(Optional.of(superAdminSession));
        when(roleRefresher.refreshIfRequired("super", superAdminSession, false))
                .thenReturn(superAdminSession);
        mvc.perform(get("/actuator/prometheus")
                        .cookie(new jakarta.servlet.http.Cookie("KB_ADMIN_SESSION", "super")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("metrics-ok"));
    }

    @Test
    void disablePublicationForcesImmediateRoleRefresh() throws Exception {
        when(sessionRepository.find("valid")).thenReturn(Optional.of(session));
        when(roleRefresher.refreshIfRequired("valid", session, true)).thenReturn(session);

        mvc.perform(post("/api/v1/admin/security-probe/disable")
                        .with(csrf())
                        .cookie(new jakarta.servlet.http.Cookie("KB_ADMIN_SESSION", "valid")))
                .andExpect(status().isOk());

        verify(roleRefresher).refreshIfRequired("valid", session, true);
    }

    @Test
    void staleRoleThatWasDisabledRejectsRequestAndDeletesSession() throws Exception {
        when(sessionRepository.find("valid")).thenReturn(Optional.of(session));
        when(roleRefresher.refreshIfRequired("valid", session, false))
                .thenThrow(new BusinessException(ApiErrorCode.KNOWLEDGE_ACCESS_DENIED));

        mvc.perform(get("/api/v1/admin/security-probe")
                        .cookie(new jakarta.servlet.http.Cookie("KB_ADMIN_SESSION", "valid")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_ACCESS_DENIED"));
    }

    @Test
    void refresherRevalidatesAfterFiveMinutesAndDeletesSessionWhenRoleDisappears() {
        SspxIdentityClient identityClient = mock(SspxIdentityClient.class);
        AdminSessionRepository repository = mock(AdminSessionRepository.class);
        Clock clock = Clock.fixed(
                session.rolesVerifiedAt().plus(Duration.ofMinutes(6)),
                ZoneOffset.UTC);
        AdminRoleRefresher refresher = new AdminRoleRefresher(
                identityClient,
                repository,
                sessionProperties,
                clock);
        when(identityClient.knowledgeRoles("access", 10567L)).thenReturn(List.of());

        assertThatThrownBy(() -> refresher.refreshIfRequired("valid", session, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ApiErrorCode.KNOWLEDGE_ACCESS_DENIED));
        verify(repository).delete("valid");
    }

}

@RestController
class AdminSecurityProbeController {

    @GetMapping("/api/v1/admin/security-probe")
    ApiResponse<String> getProbe() {
        return ApiResponse.success("ok");
    }

    @PostMapping("/api/v1/admin/security-probe")
    ApiResponse<String> postProbe() {
        return ApiResponse.success("ok");
    }

    @PostMapping("/api/v1/admin/security-probe/disable")
    ApiResponse<String> disableProbe() {
        return ApiResponse.success("disabled");
    }

    @PostMapping("/api/v1/internal/security-probe")
    ApiResponse<String> internalProbe() {
        return ApiResponse.success("internal-ok");
    }

    @GetMapping("/actuator/prometheus")
    ApiResponse<String> prometheusProbe() {
        return ApiResponse.success("metrics-ok");
    }
}

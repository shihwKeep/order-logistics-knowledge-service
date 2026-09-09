package com.xjjk.knowledge.auth.web;

import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import com.xjjk.knowledge.auth.service.AdminLoginService;
import com.xjjk.knowledge.auth.session.AdminSession;
import com.xjjk.knowledge.auth.session.AdminSessionProperties;
import com.xjjk.knowledge.auth.session.AdminSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private MockMvc mvc;
    private AdminLoginService loginService;
    private AdminSessionRepository sessionRepository;
    private AdminPrincipal principal;
    private AdminSession session;

    @BeforeEach
    void setUp() {
        loginService = mock(AdminLoginService.class);
        sessionRepository = mock(AdminSessionRepository.class);
        AdminSessionProperties properties = new AdminSessionProperties(
                "KB_ADMIN_SESSION",
                Duration.ofMinutes(30),
                Duration.ofHours(8),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                false);
        principal = new AdminPrincipal(
                10567L,
                "74680",
                "石海文",
                1L,
                Set.of(KnowledgeRole.KNOWLEDGE_ADMIN));
        Instant now = Instant.parse("2026-09-09T10:00:00Z");
        session = new AdminSession(
                principal,
                "sspx-access",
                "sspx-refresh",
                now.plusSeconds(3600),
                now,
                now,
                now);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(loginService, sessionRepository, properties))
                .build();
    }

    @Test
    void loginSetsOpaqueHttpOnlyCookieAndReturnsOnlyIdentity() throws Exception {
        when(loginService.login("74680", "secret"))
                .thenReturn(new AdminLoginService.LoginResult("browser-token", principal));

        mvc.perform(post("/api/v1/admin/auth/login")
                        .contentType("application/json")
                        .content("{\"account\":\"74680\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("KB_ADMIN_SESSION", "browser-token"))
                .andExpect(cookie().httpOnly("KB_ADMIN_SESSION", true))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(jsonPath("$.data.userId").value(10567))
                .andExpect(jsonPath("$.data.account").value("74680"))
                .andExpect(jsonPath("$.data.displayName").value("石海文"))
                .andExpect(jsonPath("$.data.tenantId").value(1))
                .andExpect(jsonPath("$.data.roles[0]").value("KNOWLEDGE_ADMIN"))
                .andExpect(content().string(not(containsString("sspx-access"))))
                .andExpect(content().string(not(containsString("sspx-refresh"))))
                .andExpect(content().string(not(containsString("test-secret"))));
    }

    @Test
    void meReadsServerSessionAndLogoutDeletesIt() throws Exception {
        when(sessionRepository.find("browser-token")).thenReturn(Optional.of(session));

        mvc.perform(get("/api/v1/admin/auth/me")
                        .cookie(new jakarta.servlet.http.Cookie("KB_ADMIN_SESSION", "browser-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(10567));

        mvc.perform(post("/api/v1/admin/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("KB_ADMIN_SESSION", "browser-token")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        verify(sessionRepository).delete("browser-token");
    }

    @Test
    void readsConfiguredCookieNameInsteadOfHardCodedDefault() throws Exception {
        AdminSessionProperties customProperties = new AdminSessionProperties(
                "CUSTOM_SESSION",
                Duration.ofMinutes(30),
                Duration.ofHours(8),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                false);
        MockMvc customMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(loginService, sessionRepository, customProperties))
                .build();
        when(sessionRepository.find("browser-token")).thenReturn(Optional.of(session));

        customMvc.perform(get("/api/v1/admin/auth/me")
                        .cookie(new jakarta.servlet.http.Cookie("CUSTOM_SESSION", "browser-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(10567));
    }
}

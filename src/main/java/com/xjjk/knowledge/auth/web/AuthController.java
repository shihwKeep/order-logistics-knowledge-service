package com.xjjk.knowledge.auth.web;

import com.xjjk.knowledge.auth.service.AdminLoginService;
import com.xjjk.knowledge.auth.session.AdminSession;
import com.xjjk.knowledge.auth.session.AdminSessionProperties;
import com.xjjk.knowledge.auth.session.AdminSessionRepository;
import com.xjjk.knowledge.auth.web.dto.AdminIdentityResponse;
import com.xjjk.knowledge.auth.web.dto.LoginRequest;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.common.error.BusinessException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 知识库管理台登录、会话查询与退出接口。 */
@RestController
@RequestMapping("/api/v1/admin/auth")
public class AuthController {

    private final AdminLoginService loginService;
    private final AdminSessionRepository sessionRepository;
    private final AdminSessionProperties sessionProperties;

    public AuthController(
            AdminLoginService loginService,
            AdminSessionRepository sessionRepository,
            AdminSessionProperties sessionProperties) {
        this.loginService = loginService;
        this.sessionRepository = sessionRepository;
        this.sessionProperties = sessionProperties;
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            token = (CsrfToken) request.getAttribute("_csrf");
        }
        if (token == null) {
            throw new BusinessException(ApiErrorCode.INTERNAL_ERROR);
        }
        return ApiResponse.success(Map.of(
                "token", token.getToken(),
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName()));
    }

    @PostMapping("/login")
    public ApiResponse<AdminIdentityResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        AdminLoginService.LoginResult result = loginService.login(
                request.account(), request.password());
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(result.browserToken()).toString());
        return ApiResponse.success(AdminIdentityResponse.from(result.principal()));
    }

    @GetMapping("/me")
    public ApiResponse<AdminIdentityResponse> me(HttpServletRequest request) {
        String browserToken = readSessionCookie(request);
        AdminSession session = sessionRepository.find(browserToken)
                .orElseThrow(() -> new BusinessException(ApiErrorCode.AUTH_REQUIRED));
        return ApiResponse.success(AdminIdentityResponse.from(session.principal()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        String browserToken = readSessionCookie(request);
        sessionRepository.delete(browserToken);
        response.addHeader(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString());
        return ApiResponse.success(null);
    }

    private ResponseCookie sessionCookie(String browserToken) {
        return ResponseCookie.from(sessionProperties.cookieName(), browserToken)
                .httpOnly(true)
                .secure(sessionProperties.secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(sessionProperties.absoluteTimeout())
                .build();
    }

    private ResponseCookie expiredSessionCookie() {
        return ResponseCookie.from(sessionProperties.cookieName(), "")
                .httpOnly(true)
                .secure(sessionProperties.secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    private String readSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (sessionProperties.cookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}

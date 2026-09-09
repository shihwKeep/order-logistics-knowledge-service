package com.xjjk.knowledge.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.service.AdminRoleRefresher;
import com.xjjk.knowledge.auth.session.AdminSession;
import com.xjjk.knowledge.auth.session.AdminSessionProperties;
import com.xjjk.knowledge.auth.session.AdminSessionRepository;
import com.xjjk.knowledge.common.api.ApiResponse;
import com.xjjk.knowledge.common.error.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** 从不透明 Cookie 恢复服务端身份，不接受浏览器传入的租户或角色请求头。 */
@Component
public class AdminSessionFilter extends OncePerRequestFilter {

    private final AdminSessionRepository sessionRepository;
    private final AdminRoleRefresher roleRefresher;
    private final AdminSessionProperties properties;
    private final ObjectMapper objectMapper;

    public AdminSessionFilter(
            AdminSessionRepository sessionRepository,
            AdminRoleRefresher roleRefresher,
            AdminSessionProperties properties,
            ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.roleRefresher = roleRefresher;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/admin/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String browserToken = readCookie(request);
        if (browserToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<AdminSession> resolved = sessionRepository.find(browserToken);
        if (resolved.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AdminSession session = roleRefresher.refreshIfRequired(
                    browserToken,
                    resolved.get(),
                    requiresFreshRoles(request));
            authenticate(session.principal());
            filterChain.doFilter(request, response);
        } catch (BusinessException exception) {
            writeBusinessError(response, exception);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(AdminPrincipal principal) {
        List<SimpleGrantedAuthority> authorities = principal.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> properties.cookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private boolean requiresFreshRoles(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "DELETE".equals(request.getMethod())
                || path.endsWith("/publish")
                || path.endsWith("/rollback")
                || path.endsWith("/disable");
    }

    private void writeBusinessError(
            HttpServletResponse response,
            BusinessException exception) throws IOException {
        response.setStatus(exception.errorCode().httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.failure(exception.errorCode(), exception.getMessage()));
    }
}

package com.xjjk.knowledge.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 管理端基于不透明 Cookie 的无状态 Spring Security 配置。 */
@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain knowledgeSecurityFilterChain(
            HttpSecurity http,
            AdminSessionFilter adminSessionFilter,
            ObjectMapper objectMapper,
            @Value("${management.server.port:18085}") int managementPort) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName("XSRF-TOKEN");
        csrfRepository.setHeaderName("X-XSRF-TOKEN");

        http
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .logout(logout -> logout.disable())
                .requestCache(requestCache -> requestCache.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        // 内部检索使用 HMAC、时间戳和一次性 nonce 鉴权，不依赖浏览器 CSRF Token。
                        .ignoringRequestMatchers("/api/v1/internal/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // 指标只在独立管理端口暴露；业务端口仍要求超级管理员会话。
                        .requestMatchers("/actuator/prometheus")
                        .access((authentication, context) -> new AuthorizationDecision(
                                context.getRequest().getLocalPort() == managementPort
                                        || authentication.get().getAuthorities().stream()
                                        .anyMatch(authority -> "ROLE_KNOWLEDGE_SUPER_ADMIN"
                                                .equals(authority.getAuthority()))))
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/internal/knowledge/retrieve").permitAll()
                        .requestMatchers("/internal/**").denyAll()
                        .requestMatchers("/api/v1/admin/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, objectMapper, ApiErrorCode.AUTH_REQUIRED))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(
                                        response,
                                        objectMapper,
                                        exception instanceof CsrfException
                                                ? ApiErrorCode.CSRF_INVALID
                                                : ApiErrorCode.KNOWLEDGE_ACCESS_DENIED)))
                .addFilterBefore(adminSessionFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            ApiErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(errorCode));
    }
}

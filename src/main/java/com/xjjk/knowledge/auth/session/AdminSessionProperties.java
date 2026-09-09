package com.xjjk.knowledge.auth.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 浏览器管理会话的 Cookie 与过期策略。 */
@Validated
@ConfigurationProperties("knowledge.admin-session")
public record AdminSessionProperties(
        @NotBlank String cookieName,
        @NotNull Duration idleTimeout,
        @NotNull Duration absoluteTimeout,
        @NotNull Duration roleCacheTtl,
        @NotNull Duration touchInterval,
        boolean secureCookie
) {
}

package com.xjjk.knowledge.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** SSPX OAuth2 与身份接口配置。客户端密钥只能来自外部配置或环境变量。 */
@Validated
@ConfigurationProperties("knowledge.sspx")
public record SspxProperties(
        @NotBlank String baseUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @Positive long applicationId
) {
}

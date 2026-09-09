package com.xjjk.knowledge.retrieval.web;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Agent 调用知识检索内部接口时使用的签名与防重放配置。 */
@Component
@ConfigurationProperties(prefix = "knowledge.retrieval.internal-api")
public class InternalApiProperties {
    private boolean enabled;
    private String secret = "";
    private Duration allowedClockSkew = Duration.ofMinutes(5);
    private Duration nonceTtl = Duration.ofMinutes(10);

    @PostConstruct
    void validate() {
        if (enabled && (secret == null || secret.length() < 32)) {
            throw new IllegalArgumentException("启用知识检索内部接口时，签名密钥至少需要 32 个字符");
        }
        if (allowedClockSkew == null || allowedClockSkew.isZero() || allowedClockSkew.isNegative()
                || nonceTtl == null || nonceTtl.isZero() || nonceTtl.isNegative()) {
            throw new IllegalArgumentException("知识检索内部接口的时间窗配置必须为正数");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public Duration getAllowedClockSkew() { return allowedClockSkew; }
    public void setAllowedClockSkew(Duration allowedClockSkew) { this.allowedClockSkew = allowedClockSkew; }
    public Duration getNonceTtl() { return nonceTtl; }
    public void setNonceTtl(Duration nonceTtl) { this.nonceTtl = nonceTtl; }
}

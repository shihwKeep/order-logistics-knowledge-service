package com.xjjk.knowledge.retrieval.web;

import com.xjjk.knowledge.common.api.ApiErrorCode;
import com.xjjk.knowledge.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/** 校验内部调用签名，并通过 Redis 一次性 nonce 阻止请求重放。 */
@Component
public class InternalRequestVerifier {
    private static final String ALGORITHM = "HmacSHA256";
    private static final String NONCE_KEY_PREFIX = "knowledge:internal:nonce:";

    private final StringRedisTemplate redis;
    private final InternalApiProperties properties;
    private final Clock clock;

    @Autowired
    public InternalRequestVerifier(StringRedisTemplate redis, InternalApiProperties properties) {
        this(redis, properties, Clock.systemUTC());
    }

    InternalRequestVerifier(StringRedisTemplate redis, InternalApiProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    public void verify(
            long tenantId,
            long userId,
            long timestamp,
            String nonce,
            String suppliedSignature,
            String question) {
        if (!properties.isEnabled() || tenantId <= 0 || userId <= 0
                || nonce == null || nonce.isBlank() || nonce.length() > 128
                || suppliedSignature == null || suppliedSignature.length() != 64
                || question == null || question.isBlank()) {
            reject();
        }
        Instant requestTime;
        try {
            requestTime = Instant.ofEpochMilli(timestamp);
        } catch (RuntimeException exception) {
            reject();
            return;
        }
        long skewMillis = Math.abs(clock.instant().toEpochMilli() - requestTime.toEpochMilli());
        if (skewMillis > properties.getAllowedClockSkew().toMillis()) {
            reject();
        }

        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(suppliedSignature);
        } catch (IllegalArgumentException exception) {
            reject();
            return;
        }
        byte[] expected = hmac(canonical(tenantId, userId, timestamp, nonce, question));
        if (!MessageDigest.isEqual(expected, supplied)) {
            reject();
        }

        // 先验签再占用 nonce，避免无效请求消耗合法调用方的 nonce。
        try {
            String nonceDigest = sha256(tenantId + "\n" + nonce);
            Boolean firstSeen = redis.opsForValue().setIfAbsent(
                    NONCE_KEY_PREFIX + nonceDigest, "1", properties.getNonceTtl());
            if (!Boolean.TRUE.equals(firstSeen)) {
                reject();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 防重放存储不可用时关闭入口，不能降级成仅验签。
            reject();
        }
    }

    static String canonical(
            long tenantId, long userId, long timestamp, String nonce, String question) {
        return "POST\n/api/v1/internal/knowledge/retrieve\n"
                + tenantId + "\n" + userId + "\n" + timestamp + "\n" + nonce + "\n"
                + sha256(question.trim());
    }

    private byte[] hmac(String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算内部请求签名", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算请求摘要", exception);
        }
    }

    private static void reject() {
        throw new BusinessException(ApiErrorCode.INTERNAL_SIGNATURE_INVALID);
    }
}

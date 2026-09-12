package com.xjjk.knowledge.retrieval.web;

import com.xjjk.knowledge.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalRequestVerifierSignedPayloadTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-09-12T07:00:00Z");

    @Test
    void bindsSignatureToPathOwnerAndPayloadDigest() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        InternalApiProperties properties = new InternalApiProperties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        InternalRequestVerifier verifier = new InternalRequestVerifier(
                redis, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        long timestamp = NOW.toEpochMilli();
        String path = "/api/v1/internal/user-memories/retrieve";
        String digest = "a".repeat(64);
        String signature = sign(InternalRequestVerifier.canonicalSignedPayload(
                path, 1L, 74680L, timestamp, "nonce-1", digest));

        verifier.verifySignedPayload(
                path, 1L, 74680L, timestamp, "nonce-1", signature, digest);

        assertThatThrownBy(() -> verifier.verifySignedPayload(
                "/api/v1/internal/user-memories/index-events",
                1L, 74680L, timestamp, "nonce-2", signature, digest))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verifier.verifySignedPayload(
                path, 1L, 74680L, timestamp, "nonce-3", signature, "b".repeat(64)))
                .isInstanceOf(BusinessException.class);
    }

    private static String sign(String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
}

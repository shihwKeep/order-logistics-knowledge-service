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

class InternalRequestVerifierTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-09-09T13:00:00Z");

    @Test
    void acceptsValidSignatureAndRejectsReplayTamperingAndExpiredTimestamp() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false);
        InternalApiProperties properties = new InternalApiProperties();
        properties.setEnabled(true);
        properties.setSecret(SECRET);
        InternalRequestVerifier verifier = new InternalRequestVerifier(
                redis, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        long timestamp = NOW.toEpochMilli();
        String signature = sign(1L, 10567L, timestamp, "nonce-1", "怎么退款");

        verifier.verify(1L, 10567L, timestamp, "nonce-1", signature, "怎么退款");
        assertThatThrownBy(() -> verifier.verify(
                1L, 10567L, timestamp, "nonce-1", signature, "怎么退款"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verifier.verify(
                1L, 10567L, timestamp, "nonce-2", signature, "篡改问题"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> verifier.verify(
                1L, 10567L, NOW.minusSeconds(600).toEpochMilli(), "nonce-3", signature, "怎么退款"))
                .isInstanceOf(BusinessException.class);
    }

    private String sign(long tenantId, long userId, long timestamp, String nonce, String question) throws Exception {
        String canonical = InternalRequestVerifier.canonical(tenantId, userId, timestamp, nonce, question);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
}

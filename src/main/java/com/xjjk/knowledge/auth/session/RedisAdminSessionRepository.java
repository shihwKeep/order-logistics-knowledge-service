package com.xjjk.knowledge.auth.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Redis 管理会话实现。
 * 浏览器持有随机令牌，Redis 键只使用令牌的 SHA-256 摘要，降低存储泄漏后的冒用风险。
 */
@Repository
public class RedisAdminSessionRepository implements AdminSessionRepository {

    private static final String KEY_PREFIX = "kb:admin:session:";
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AdminSessionProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public RedisAdminSessionRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AdminSessionProperties properties) {
        this(redisTemplate, objectMapper, properties, Clock.systemUTC());
    }

    public RedisAdminSessionRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AdminSessionProperties properties,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String create(AdminSession session) {
        Objects.requireNonNull(session, "session");
        String browserToken = generateBrowserToken();
        persist(storageKey(browserToken), session, clock.instant());
        return browserToken;
    }

    @Override
    public Optional<AdminSession> find(String browserToken) {
        if (browserToken == null || browserToken.isBlank()) {
            return Optional.empty();
        }
        String key = storageKey(browserToken);
        String serialized = redisTemplate.opsForValue().get(key);
        if (serialized == null) {
            return Optional.empty();
        }

        AdminSession session;
        try {
            session = objectMapper.readValue(serialized, AdminSession.class);
        } catch (JsonProcessingException exception) {
            // 无法解析的会话不可继续信任，直接失效，不把原始内容写入日志。
            redisTemplate.delete(key);
            return Optional.empty();
        }

        Instant now = clock.instant();
        if (isExpired(session, now)) {
            redisTemplate.delete(key);
            return Optional.empty();
        }

        Duration sinceLastTouch = Duration.between(session.lastAccessAt(), now);
        if (sinceLastTouch.compareTo(properties.touchInterval()) >= 0) {
            session = session.withLastAccessAt(now);
            persist(key, session, now);
        }
        return Optional.of(session);
    }

    @Override
    public void save(String browserToken, AdminSession session) {
        if (browserToken == null || browserToken.isBlank()) {
            throw new IllegalArgumentException("browserToken must not be blank");
        }
        persist(storageKey(browserToken), session, clock.instant());
    }

    @Override
    public void delete(String browserToken) {
        if (browserToken != null && !browserToken.isBlank()) {
            redisTemplate.delete(storageKey(browserToken));
        }
    }

    private void persist(String key, AdminSession session, Instant now) {
        Duration ttl = effectiveTtl(session, now);
        if (ttl.isZero() || ttl.isNegative()) {
            redisTemplate.delete(key);
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(session), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize admin session", exception);
        }
    }

    private boolean isExpired(AdminSession session, Instant now) {
        Instant idleDeadline = session.lastAccessAt().plus(properties.idleTimeout());
        Instant absoluteDeadline = session.createdAt().plus(properties.absoluteTimeout());
        return !now.isBefore(idleDeadline) || !now.isBefore(absoluteDeadline);
    }

    private Duration effectiveTtl(AdminSession session, Instant now) {
        Duration absoluteRemaining = Duration.between(
                now,
                session.createdAt().plus(properties.absoluteTimeout()));
        return absoluteRemaining.compareTo(properties.idleTimeout()) < 0
                ? absoluteRemaining
                : properties.idleTimeout();
    }

    private String generateBrowserToken() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String storageKey(String browserToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(browserToken.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

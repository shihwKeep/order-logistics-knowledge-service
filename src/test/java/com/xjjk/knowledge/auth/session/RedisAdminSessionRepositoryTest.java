package com.xjjk.knowledge.auth.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xjjk.knowledge.auth.domain.AdminPrincipal;
import com.xjjk.knowledge.auth.domain.KnowledgeRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisAdminSessionRepositoryTest {

    private final AtomicReference<String> redisKey = new AtomicReference<>();
    private final AtomicReference<String> redisValue = new AtomicReference<>();
    private MutableClock clock;
    private ObjectMapper objectMapper;
    private RedisAdminSessionRepository repository;
    private AdminSession session;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-09T10:00:00Z"));
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key.equals(redisKey.get()) ? redisValue.get() : null;
        });
        doAnswer(invocation -> {
            redisKey.set(invocation.getArgument(0));
            redisValue.set(invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.delete(anyString())).thenAnswer(invocation -> {
            if (invocation.getArgument(0).equals(redisKey.get())) {
                redisValue.set(null);
                return true;
            }
            return false;
        });

        AdminSessionProperties properties = new AdminSessionProperties(
                "KB_ADMIN_SESSION",
                Duration.ofMinutes(30),
                Duration.ofHours(8),
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                false);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        repository = new RedisAdminSessionRepository(
                redisTemplate,
                objectMapper,
                properties,
                clock);
        Instant now = clock.instant();
        session = new AdminSession(
                new AdminPrincipal(
                        10567L,
                        "74680",
                        "石海文",
                        1L,
                        Set.of(KnowledgeRole.KNOWLEDGE_ADMIN)),
                "sspx-access",
                "sspx-refresh",
                now.plusSeconds(3600),
                now,
                now,
                now);
    }

    @Test
    void storesOnlyDigestAndCanFindAndDeleteSession() throws Exception {
        String browserToken = repository.create(session);

        assertThat(browserToken).hasSizeGreaterThanOrEqualTo(43);
        assertThat(redisKey.get())
                .startsWith("kb:admin:session:")
                .doesNotContain(browserToken);
        assertThat(redisValue.get()).doesNotContain(browserToken);
        assertThat(objectMapper.readValue(redisValue.get(), AdminSession.class)).isEqualTo(session);
        assertThat(repository.find(browserToken)).contains(session);

        repository.delete(browserToken);

        assertThat(repository.find(browserToken)).isEmpty();
    }

    @Test
    void rejectsSessionAfterIdleTimeout() {
        String browserToken = repository.create(session);
        clock.advance(Duration.ofMinutes(30).plusSeconds(1));

        assertThat(repository.find(browserToken)).isEmpty();
    }

    @Test
    void refreshesIdleTimeButNeverExtendsAbsoluteLifetime() {
        String browserToken = repository.create(session);

        Optional<AdminSession> found = Optional.of(session);
        for (int index = 0; index < 16; index++) {
            clock.advance(Duration.ofMinutes(29));
            found = repository.find(browserToken);
            assertThat(found).isPresent();
        }

        clock.advance(Duration.ofMinutes(17));

        assertThat(repository.find(browserToken)).isEmpty();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

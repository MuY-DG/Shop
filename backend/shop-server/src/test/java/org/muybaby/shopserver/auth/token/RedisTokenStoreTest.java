package org.muybaby.shopserver.auth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTokenStoreTest {

    @Test
    void roundTripsTokenSessionThroughJacksonSerialization() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        RedisTokenStore store = new RedisTokenStore(redisTemplate, objectMapper);
        TokenSession session = new TokenSession(
                "session-1",
                TokenKind.ADMIN,
                1L,
                "Super",
                List.of("R_SUPER"),
                List.of("system:user:create"),
                Instant.parse("2026-07-06T12:00:00Z")
        );

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store.save("shop:auth:admin:access:hash", session, Duration.ofHours(2));
        String serializedSession = capturedSerializedSession(valueOperations);
        when(valueOperations.get("shop:auth:admin:access:hash")).thenReturn(serializedSession);

        Optional<TokenSession> roundTripped = store.find("shop:auth:admin:access:hash");
        TokenSession actualSession = roundTripped.orElseThrow();

        assertThat(roundTripped).contains(session);
        assertThat(actualSession.kind()).isEqualTo(TokenKind.ADMIN);
        assertThat(actualSession.issuedAt()).isEqualTo(Instant.parse("2026-07-06T12:00:00Z"));
        assertThat(actualSession.roles()).containsExactly("R_SUPER");
        assertThat(actualSession.permissions()).containsExactly("system:user:create");
    }

    private String capturedSerializedSession(ValueOperations<String, String> valueOperations) {
        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("shop:auth:admin:access:hash"), sessionCaptor.capture(), eq(Duration.ofHours(2)));
        return sessionCaptor.getValue();
    }
}

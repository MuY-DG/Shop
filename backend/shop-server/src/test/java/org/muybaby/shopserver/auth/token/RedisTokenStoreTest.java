package org.muybaby.shopserver.auth.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

class RedisTokenStoreTest {

    private static final String GENERATION_ID = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF";
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void roundTripsTokenSessionThroughJacksonSerialization() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        RedisTokenStore store = new RedisTokenStore(redisTemplate, objectMapper);
        TokenSession session = adminSession();
        String key = "shop:auth:admin:access:" + "a".repeat(64);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(objectMapper.writeValueAsString(session));

        Optional<TokenSession> roundTripped = store.find(key);
        TokenSession actualSession = roundTripped.orElseThrow();

        assertThat(roundTripped).contains(session);
        assertThat(actualSession.kind()).isEqualTo(TokenKind.ADMIN);
        assertThat(actualSession.issuedAt()).isEqualTo(Instant.parse("2026-07-06T12:00:00Z"));
        assertThat(actualSession.roles()).containsExactly("R_SUPER");
        assertThat(actualSession.permissions()).containsExactly("system:user:create");
    }

    @Test
    void legacyJsonWithMissingOrBlankGenerationFallsBackToSessionId() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        RedisTokenStore store = new RedisTokenStore(redisTemplate, objectMapper);
        ObjectNode missingGeneration = objectMapper.valueToTree(adminSession());
        missingGeneration.remove("generationId");
        missingGeneration.remove("authVersion");
        ObjectNode blankGeneration = objectMapper.valueToTree(adminSession());
        blankGeneration.put("generationId", "   ");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("missing-generation"))
                .thenReturn(objectMapper.writeValueAsString(missingGeneration));
        when(valueOperations.get("blank-generation"))
                .thenReturn(objectMapper.writeValueAsString(blankGeneration));

        TokenSession legacy = store.find("missing-generation").orElseThrow();
        assertThat(legacy.generationId()).isEqualTo("session-1");
        assertThat(legacy.authVersion()).isZero();
        assertThat(store.find("blank-generation").orElseThrow().generationId()).isEqualTo("session-1");
    }

    @Test
    void saveFamilyUsesOneLuaCallAndOnlyHashedKeysForIndexMembers() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisTokenStore store = new RedisTokenStore(redisTemplate, objectMapper);
        TokenSession session = adminSession();
        String rawAccess = "adm_raw-access-token";
        String rawRefresh = "adr_raw-refresh-token";
        String accessKey = "shop:auth:admin:access:" + "a".repeat(64);
        String refreshKey = "shop:auth:admin:refresh:" + "b".repeat(64);

        store.saveFamily(session.sessionId(), List.of(
                new TokenGrant(accessKey, session, Duration.ofHours(2)),
                new TokenGrant(refreshKey, session, Duration.ofDays(7))
        ));

        Collection<Invocation> executions = mockingDetails(redisTemplate).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute"))
                .toList();
        assertThat(executions).hasSize(1);
        Invocation execution = executions.iterator().next();
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) execution.getArguments()[1];
        RedisScript<?> script = (RedisScript<?>) execution.getArguments()[0];

        assertThat(keys).containsExactly(
                "shop:auth:session:" + session.sessionId(),
                "shop:auth:revoked:" + session.sessionId(),
                accessKey,
                refreshKey
        );
        assertThat(keys).allSatisfy(key -> assertThat(key).doesNotContain(rawAccess, rawRefresh));
        assertThat(script.getScriptAsString()).contains("SADD", "PEXPIRE");
        assertThat(Arrays.deepToString(execution.getArguments())).doesNotContain(rawAccess, rawRefresh);
    }

    @Test
    void consumeAndLogoutEachUseOneLuaInvocation() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisTokenStore store = new RedisTokenStore(redisTemplate, objectMapper);
        TokenSession session = adminSession();
        String refreshKey = "shop:auth:admin:refresh:" + "b".repeat(64);

        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<String>>any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(objectMapper.writeValueAsString(session));

        assertThat(store.consumeRefreshAndRevokeFamily(refreshKey, Duration.ofDays(7))).contains(session);
        store.revokeSession(session.sessionId(), Duration.ofDays(7));

        List<Invocation> executions = mockingDetails(redisTemplate).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute"))
                .toList();
        assertThat(executions).hasSize(2);
        assertThat(((RedisScript<?>) executions.get(0).getArguments()[0]).getScriptAsString())
                .contains("GET", "SMEMBERS", "DEL");
        assertThat(((RedisScript<?>) executions.get(1).getArguments()[0]).getScriptAsString())
                .contains("SET", "SMEMBERS", "DEL");
    }

    private TokenSession adminSession() {
        return new TokenSession(
                "session-1",
                GENERATION_ID,
                TokenKind.ADMIN,
                1L,
                "Super",
                List.of("R_SUPER"),
                List.of("system:user:create"),
                Instant.parse("2026-07-06T12:00:00Z")
        );
    }
}

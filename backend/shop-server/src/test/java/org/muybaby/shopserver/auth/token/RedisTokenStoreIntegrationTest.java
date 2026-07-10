package org.muybaby.shopserver.auth.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class RedisTokenStoreIntegrationTest {

    private static final String GENERATION_ID = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF";
    private static final String SECOND_GENERATION_ID = "123E4567-E89B-12D3-A456-426614174000";
    private static final String OLD_GENERATION_ID = "22222222-2222-4222-8222-222222222222";
    private static final String CURRENT_GENERATION_ID = "33333333-3333-4333-8333-333333333333";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:latest"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RedisTokenStore tokenStore;
    private OpaqueTokenService tokenService;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        tokenStore = new RedisTokenStore(redisTemplate, objectMapper());
        tokenService = new OpaqueTokenService(tokenStore, properties());
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void familyIssueIsAllOrNothingAndStoresNoRawTokens() throws Exception {
        TokenSession session = appSession("session-issue");
        TokenPair pair = tokenService.issue(TokenKind.APP, session);

        Set<String> redisKeys = redisTemplate.keys("shop:auth:*");
        String indexKey = "shop:auth:session:" + session.sessionId();
        Set<String> familyMembers = redisTemplate.opsForSet().members(indexKey);

        assertThat(redisKeys).hasSize(3);
        assertThat(familyMembers).hasSize(2);
        assertThat(familyMembers).allMatch(redisKeys::contains);
        assertThat(redisKeys).allSatisfy(key -> assertThat(key)
                .doesNotContain(pair.accessToken(), pair.refreshToken()));
        assertThat(familyMembers).allSatisfy(key -> assertThat(key)
                .doesNotContain(pair.accessToken(), pair.refreshToken())
                .matches("shop:auth:app:(access|refresh):[0-9a-f]{64}"));
        String accessKey = familyMembers.stream()
                .filter(key -> key.startsWith("shop:auth:app:access:"))
                .findFirst()
                .orElseThrow();
        String refreshKey = familyMembers.stream()
                .filter(key -> key.startsWith("shop:auth:app:refresh:"))
                .findFirst()
                .orElseThrow();
        assertTtlNear(accessKey, Duration.ofHours(1));
        assertTtlNear(refreshKey, Duration.ofDays(1));
        assertTtlNear(indexKey, Duration.ofDays(1));

        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenReturn(objectMapper().writeValueAsString(session))
                .thenThrow(new JsonProcessingException("serialization failed") {
                });
        RedisTokenStore failingStore = new RedisTokenStore(redisTemplate, failingMapper);
        TokenSession failedSession = appSession("session-failed-issue");

        assertThatThrownBy(() -> failingStore.saveFamily(failedSession.sessionId(), List.of(
                grant("shop:auth:app:access:" + "c".repeat(64), failedSession, Duration.ofHours(1)),
                grant("shop:auth:app:refresh:" + "d".repeat(64), failedSession, Duration.ofDays(1))
        ))).isInstanceOf(IllegalStateException.class);
        assertThat(redisTemplate.keys("*failed-issue*")).isEmpty();
        assertThat(redisTemplate.hasKey("shop:auth:app:access:" + "c".repeat(64))).isFalse();
        assertThat(redisTemplate.hasKey("shop:auth:app:refresh:" + "d".repeat(64))).isFalse();
    }

    @Test
    void familyIssueDoesNotPartiallyWriteWhenSessionIndexHasWrongType() {
        TokenSession session = appSession("session-wrong-index-type");
        String indexKey = "shop:auth:session:" + session.sessionId();
        String accessKey = "shop:auth:app:access:" + "e".repeat(64);
        String refreshKey = "shop:auth:app:refresh:" + "f".repeat(64);
        redisTemplate.opsForValue().set(indexKey, "corrupt-index");

        assertThatThrownBy(() -> tokenStore.saveFamily(session.sessionId(), List.of(
                grant(accessKey, session, Duration.ofHours(1)),
                grant(refreshKey, session, Duration.ofDays(1))
        ))).isInstanceOf(IllegalStateException.class);

        assertThat(redisTemplate.hasKey(accessKey)).isFalse();
        assertThat(redisTemplate.hasKey(refreshKey)).isFalse();
        assertThat(redisTemplate.opsForValue().get(indexKey)).isEqualTo("corrupt-index");
    }

    @Test
    void familyMarkerRejectsARefreshGenerationWithoutWritingTokens() {
        TokenSession session = appSession("family-logout-before-issue");
        tokenService.revokeSession(session.sessionId(), TokenKind.APP);

        assertAuthenticationRequired(() -> tokenService.issue(TokenKind.APP, session));

        assertThat(redisTemplate.keys("shop:auth:app:*")).isEmpty();
        assertThat(redisTemplate.hasKey("shop:auth:session:" + session.sessionId())).isFalse();
        assertThat(redisTemplate.hasKey("shop:auth:revoked:" + session.sessionId())).isTrue();
    }

    @Test
    void familyIndexAndConsumedGenerationMarkerUseTheRefreshLifetime() {
        TokenSession session = new TokenSession(
                "family-marker-ttl",
                GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
        TokenPair pair = tokenService.issue(TokenKind.APP, session);

        assertTtlNear("shop:auth:session:" + session.sessionId(), Duration.ofDays(1));

        assertThat(tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP)).isEqualTo(session);
        assertTtlNear("shop:auth:generation-revoked:" + session.generationId(), Duration.ofDays(1));
        assertThat(redisTemplate.hasKey("shop:auth:revoked:" + session.sessionId())).isFalse();
    }

    @Test
    void markersUseTheLongestTokenLifetimeWhenAccessOutlivesRefresh() {
        OpaqueTokenService invertedTtlService = new OpaqueTokenService(
                tokenStore,
                new TokenProperties(
                        Duration.ofHours(2),
                        Duration.ofDays(7),
                        Duration.ofDays(2),
                        Duration.ofDays(1)
                )
        );
        TokenSession consumedSession = new TokenSession(
                "inverted-consume-family",
                GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
        TokenPair consumedPair = invertedTtlService.issue(TokenKind.APP, consumedSession);
        String consumedIndex = "shop:auth:session:" + consumedSession.sessionId();
        redisTemplate.delete(consumedIndex);

        invertedTtlService.consumeRefreshToken(consumedPair.refreshToken(), TokenKind.APP);

        assertTtlNear(
                "shop:auth:generation-revoked:" + consumedSession.generationId(),
                Duration.ofDays(2)
        );

        TokenSession logoutSession = new TokenSession(
                "inverted-logout-family",
                SECOND_GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
        invertedTtlService.issue(TokenKind.APP, logoutSession);
        redisTemplate.delete("shop:auth:session:" + logoutSession.sessionId());

        invertedTtlService.revokeSession(logoutSession.sessionId(), TokenKind.APP);

        assertTtlNear("shop:auth:revoked:" + logoutSession.sessionId(), Duration.ofDays(2));
    }

    @Test
    void logoutDeletesAnIndexedFamilyAndKeepsAFamilyMarkerForTheRefreshLifetime() {
        TokenSession session = appSession("family-logout-happy-path");
        TokenPair pair = tokenService.issue(TokenKind.APP, session);

        tokenService.revokeSession(session.sessionId(), TokenKind.APP);

        assertThat(redisTemplate.keys("shop:auth:app:*")).isEmpty();
        assertThat(redisTemplate.hasKey("shop:auth:session:" + session.sessionId())).isFalse();
        assertTtlNear("shop:auth:revoked:" + session.sessionId(), Duration.ofDays(1));
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).isEmpty();
        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP));
    }

    @Test
    void concurrentRefreshConsumeHasExactlyOneWinnerAndDeletesFamily() throws Exception {
        TokenSession session = appSession("session-concurrent");
        TokenPair pair = tokenService.issue(TokenKind.APP, session);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> consumeAfterBarrier(barrier, pair.refreshToken()));
            Future<Boolean> second = executor.submit(() -> consumeAfterBarrier(barrier, pair.refreshToken()));

            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).isEmpty();
        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP));
        assertThat(redisTemplate.keys("shop:auth:app:*")).isEmpty();
        assertThat(redisTemplate.hasKey("shop:auth:session:" + session.sessionId())).isFalse();
        assertThat(redisTemplate.hasKey("shop:auth:revoked:" + session.sessionId())).isFalse();
        assertThat(redisTemplate.hasKey("shop:auth:generation-revoked:" + session.generationId())).isTrue();
    }

    @Test
    void consumeRefreshFailsClosedWhenFamilyIndexHasWrongType() {
        TokenSession session = new TokenSession(
                "family-corrupt-consume",
                GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
        TokenPair pair = tokenService.issue(TokenKind.APP, session);
        String indexKey = "shop:auth:session:" + session.sessionId();
        String accessKey = redisTemplate.keys("shop:auth:app:access:*").iterator().next();
        String refreshKey = redisTemplate.keys("shop:auth:app:refresh:*").iterator().next();
        redisTemplate.delete(indexKey);
        redisTemplate.opsForValue().set(indexKey, "corrupt-index");

        assertThat(tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP)).isEqualTo(session);

        assertThat(redisTemplate.hasKey(indexKey)).isFalse();
        assertThat(redisTemplate.hasKey(refreshKey)).isFalse();
        assertThat(redisTemplate.hasKey(accessKey)).isTrue();
        assertThat(redisTemplate.hasKey("shop:auth:generation-revoked:" + session.generationId())).isTrue();
        assertThat(redisTemplate.hasKey("shop:auth:revoked:" + session.sessionId())).isFalse();
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).isEmpty();
    }

    @Test
    void refreshRacingLogoutLeavesNoUsableOldToken() throws Exception {
        TokenSession session = appSession("session-refresh-logout-race");
        TokenPair pair = tokenService.issue(TokenKind.APP, session);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> refresh = executor.submit(() -> consumeAfterBarrier(barrier, pair.refreshToken()));
            Future<?> logout = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                tokenService.revokeSession(session.sessionId(), TokenKind.APP);
                return null;
            });
            assertThat(refresh.get(5, TimeUnit.SECONDS)).isIn(true, false);
            logout.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).isEmpty();
        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP));
        assertThat(redisTemplate.keys("shop:auth:app:*")).isEmpty();
        assertThat(redisTemplate.hasKey("shop:auth:session:" + session.sessionId())).isFalse();
        assertThat(redisTemplate.hasKey("shop:auth:revoked:" + session.sessionId())).isTrue();
    }

    @Test
    void logoutFailsClosedWhenFamilyIndexHasWrongType() {
        TokenSession session = new TokenSession(
                "family-corrupt-logout",
                GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
        TokenPair pair = tokenService.issue(TokenKind.APP, session);
        String indexKey = "shop:auth:session:" + session.sessionId();
        String accessKey = redisTemplate.keys("shop:auth:app:access:*").iterator().next();
        String refreshKey = redisTemplate.keys("shop:auth:app:refresh:*").iterator().next();
        redisTemplate.delete(indexKey);
        redisTemplate.opsForValue().set(indexKey, "corrupt-index");

        tokenService.revokeSession(session.sessionId(), TokenKind.APP);

        assertThat(redisTemplate.hasKey(indexKey)).isFalse();
        assertThat(redisTemplate.hasKey(accessKey)).isTrue();
        assertThat(redisTemplate.hasKey(refreshKey)).isTrue();
        assertThat(redisTemplate.hasKey("shop:auth:revoked:" + session.sessionId())).isTrue();
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).isEmpty();
        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP));
        assertThat(redisTemplate.hasKey(refreshKey)).isFalse();
    }

    @Test
    void legacySessionWithoutIndexIsRejectedByMarkerAndStaleRefreshIsDeleted() {
        TokenSession session = appSession("legacy-session");
        TokenPair pair = tokenService.issue(TokenKind.APP, session);
        String indexKey = "shop:auth:session:" + session.sessionId();
        String accessKey = redisTemplate.keys("shop:auth:app:access:*").iterator().next();
        String refreshKey = redisTemplate.keys("shop:auth:app:refresh:*").iterator().next();
        redisTemplate.delete(indexKey);

        tokenService.revokeSession(session.sessionId(), TokenKind.APP);

        assertThat(redisTemplate.hasKey(accessKey)).isTrue();
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).isEmpty();
        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP));
        assertThat(redisTemplate.hasKey(refreshKey)).isFalse();
        assertThat(redisTemplate.hasKey("shop:auth:revoked:" + session.sessionId())).isTrue();
    }

    @Test
    void replayedLegacyRefreshCannotDeleteTheCurrentGeneration() throws Exception {
        TokenSession oldSession = new TokenSession(
                "stable-family",
                OLD_GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
        TokenPair oldPair = tokenService.issue(TokenKind.APP, oldSession);
        String oldRefreshKey = redisTemplate.keys("shop:auth:app:refresh:*").iterator().next();
        assertThat(tokenService.consumeRefreshToken(oldPair.refreshToken(), TokenKind.APP)).isEqualTo(oldSession);
        TokenSession currentSession = new TokenSession(
                oldSession.sessionId(),
                CURRENT_GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T01:00:00Z")
        );
        TokenPair currentPair = tokenService.issue(TokenKind.APP, currentSession);
        redisTemplate.opsForValue().set(
                oldRefreshKey,
                objectMapper().writeValueAsString(oldSession),
                Duration.ofDays(1)
        );

        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(oldPair.refreshToken(), TokenKind.APP));

        assertThat(tokenService.lookupAccessToken(currentPair.accessToken(), TokenKind.APP))
                .contains(currentSession);
    }

    @Test
    void whitespaceLegacyGenerationUsesTheSessionIdMarkerWithoutAnIndex() throws Exception {
        TokenSession session = new TokenSession(
                "whitespace-family",
                GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
        TokenPair pair = tokenService.issue(TokenKind.APP, session);
        String indexKey = "shop:auth:session:" + session.sessionId();
        String accessKey = redisTemplate.keys("shop:auth:app:access:*").iterator().next();
        String refreshKey = redisTemplate.keys("shop:auth:app:refresh:*").iterator().next();
        ObjectNode legacyPayload = objectMapper().valueToTree(session);
        legacyPayload.put("generationId", "   ");
        String serialized = objectMapper().writeValueAsString(legacyPayload);
        redisTemplate.opsForValue().set(accessKey, serialized, Duration.ofDays(2));
        redisTemplate.opsForValue().set(refreshKey, serialized, Duration.ofDays(1));
        redisTemplate.delete(indexKey);

        TokenSession consumed = tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP);

        assertThat(consumed.generationId()).isEqualTo(session.sessionId());
        assertThat(redisTemplate.hasKey(accessKey)).isTrue();
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).isEmpty();
        assertThat(redisTemplate.hasKey("shop:auth:generation-revoked:" + session.sessionId())).isTrue();
        assertThat(redisTemplate.hasKey("shop:auth:generation-revoked:   ")).isFalse();
    }

    @Test
    void explicitNullLegacyGenerationUsesTheSessionIdMarkerWithoutAnIndex() throws Exception {
        assertInvalidLegacyGenerationFallsBackToSessionId(
                "null-family",
                payload -> payload.putNull("generationId")
        );
    }

    @Test
    void unicodeWhitespaceLegacyGenerationUsesTheSessionIdMarkerWithoutAnIndex() throws Exception {
        assertInvalidLegacyGenerationFallsBackToSessionId(
                "unicode-whitespace-family",
                payload -> payload.put("generationId", "\u2003")
        );
        assertThat(redisTemplate.hasKey("shop:auth:generation-revoked:\u2003")).isFalse();
    }

    @Test
    void numericLegacyGenerationUsesTheSessionIdMarkerWithoutAnIndex() throws Exception {
        assertInvalidLegacyGenerationFallsBackToSessionId(
                "numeric-family",
                payload -> payload.put("generationId", 42)
        );
        assertThat(redisTemplate.hasKey("shop:auth:generation-revoked:42")).isFalse();
    }

    @Test
    void malformedStringLegacyGenerationUsesTheSessionIdMarkerWithoutAnIndex() throws Exception {
        assertInvalidLegacyGenerationFallsBackToSessionId(
                "malformed-generation-family",
                payload -> payload.put("generationId", "not-a-uuid")
        );
        assertThat(redisTemplate.hasKey("shop:auth:generation-revoked:not-a-uuid")).isFalse();
    }

    private boolean consumeAfterBarrier(CyclicBarrier barrier, String refreshToken) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            tokenService.consumeRefreshToken(refreshToken, TokenKind.APP);
            return true;
        } catch (BusinessException ex) {
            assertThat(ex.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
            return false;
        }
    }

    private void assertAuthenticationRequired(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    private void assertTtlNear(String key, Duration expected) {
        Long ttlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).isBetween(expected.minusSeconds(5).toMillis(), expected.toMillis());
    }

    private void assertInvalidLegacyGenerationFallsBackToSessionId(
            String sessionId,
            java.util.function.Consumer<ObjectNode> corruptGeneration
    ) throws Exception {
        TokenSession session = new TokenSession(
                sessionId,
                GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
        TokenPair pair = tokenService.issue(TokenKind.APP, session);
        String indexKey = "shop:auth:session:" + session.sessionId();
        String accessKey = redisTemplate.keys("shop:auth:app:access:*").iterator().next();
        String refreshKey = redisTemplate.keys("shop:auth:app:refresh:*").iterator().next();
        ObjectNode legacyPayload = objectMapper().valueToTree(session);
        corruptGeneration.accept(legacyPayload);
        String serialized = objectMapper().writeValueAsString(legacyPayload);
        redisTemplate.opsForValue().set(accessKey, serialized, Duration.ofDays(2));
        redisTemplate.opsForValue().set(refreshKey, serialized, Duration.ofDays(1));
        redisTemplate.delete(indexKey);

        TokenSession consumed = tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP);

        assertThat(consumed.generationId()).isEqualTo(session.sessionId());
        assertThat(redisTemplate.hasKey(accessKey)).isTrue();
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).isEmpty();
        assertThat(redisTemplate.hasKey("shop:auth:generation-revoked:" + session.sessionId())).isTrue();
    }

    private TokenSession appSession(String sessionId) {
        return new TokenSession(
                sessionId,
                GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-09T00:00:00Z")
        );
    }

    private TokenGrant grant(String key, TokenSession session, Duration ttl) {
        return new TokenGrant(key, session, ttl);
    }

    private TokenProperties properties() {
        return new TokenProperties(
                Duration.ofHours(2),
                Duration.ofDays(7),
                Duration.ofHours(1),
                Duration.ofDays(1)
        );
    }

    private ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json().build();
    }
}

package org.muybaby.shopserver.auth.token;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        Set<String> familyMembers = redisTemplate.opsForSet().members("shop:auth:session:" + session.sessionId());

        assertThat(redisKeys).hasSize(3);
        assertThat(familyMembers).hasSize(2);
        assertThat(familyMembers).allMatch(redisKeys::contains);
        assertThat(redisKeys).allSatisfy(key -> assertThat(key)
                .doesNotContain(pair.accessToken(), pair.refreshToken()));
        assertThat(familyMembers).allSatisfy(key -> assertThat(key)
                .doesNotContain(pair.accessToken(), pair.refreshToken())
                .matches("shop:auth:app:(access|refresh):[0-9a-f]{64}"));

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
        assertThat(redisTemplate.hasKey("shop:auth:revoked:" + session.sessionId())).isTrue();
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

    private TokenSession appSession(String sessionId) {
        return new TokenSession(
                sessionId,
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

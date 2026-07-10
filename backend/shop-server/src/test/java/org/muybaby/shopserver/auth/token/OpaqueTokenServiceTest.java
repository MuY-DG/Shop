package org.muybaby.shopserver.auth.token;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpaqueTokenServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTokenStore tokenStore = new InMemoryTokenStore(clock);
    private final TokenProperties properties = properties(Duration.ofDays(7), Duration.ofDays(30));
    private final OpaqueTokenService tokenService = new OpaqueTokenService(tokenStore, properties);

    @Test
    void issueAdminTokensWithAdminPrefixesAndLookupSessionByAccessToken() {
        TokenSession session = TokenSession.admin(
                1L,
                "Super",
                List.of("R_SUPER"),
                List.of("system:user:create"),
                clock.instant()
        );

        TokenPair pair = tokenService.issue(TokenKind.ADMIN, session);

        assertThat(pair.accessToken()).startsWith("adm_");
        assertThat(pair.refreshToken()).startsWith("adr_");
        assertThat(pair.expiresIn()).isEqualTo(7200);
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.ADMIN)).contains(session);
    }

    @Test
    void issueAppFamilyOnceWithOnlyHashedKeys() {
        RecordingTokenStore recordingStore = new RecordingTokenStore();
        OpaqueTokenService service = new OpaqueTokenService(recordingStore, properties);
        TokenSession session = TokenSession.app(9L, "openid-user", clock.instant());

        TokenPair pair = service.issue(TokenKind.APP, session);

        assertThat(recordingStore.saveCalls()).isEqualTo(1);
        assertThat(recordingStore.savedSessionId()).isEqualTo(session.sessionId());
        assertThat(recordingStore.savedGrants()).hasSize(2);
        assertThat(recordingStore.savedGrants())
                .allSatisfy(grant -> {
                    assertThat(grant.session()).isEqualTo(session);
                    assertThat(grant.key()).doesNotContain(pair.accessToken(), pair.refreshToken());
                    assertThat(grant.key()).matches("shop:auth:app:(access|refresh):[0-9a-f]{64}");
                });
        assertThat(service.lookupAccessToken(pair.accessToken(), TokenKind.ADMIN)).isEmpty();
    }

    @Test
    void rejectMismatchedKindSessionWithoutPersistingTokens() {
        RecordingTokenStore recordingStore = new RecordingTokenStore();
        OpaqueTokenService service = new OpaqueTokenService(recordingStore, properties);
        TokenSession appSession = TokenSession.app(9L, "openid-user", clock.instant());

        assertThatThrownBy(() -> service.issue(TokenKind.ADMIN, appSession))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Token session kind does not match requested token kind");

        assertThat(recordingStore.savedGrants()).isEmpty();
    }

    @Test
    void refreshTokenCanBeConsumedExactlyOnceUnderConcurrency() throws Exception {
        TokenSession session = TokenSession.app(9L, "openid-user", clock.instant());
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
        assertThat(tokenStore.isSessionRevoked(session.sessionId())).isTrue();
        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP));
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-07-06T12:00:00Z"));
        InMemoryTokenStore expiringStore = new InMemoryTokenStore(mutableClock);
        OpaqueTokenService service = new OpaqueTokenService(
                expiringStore,
                properties(Duration.ofDays(7), Duration.ofSeconds(5))
        );
        TokenPair pair = service.issue(
                TokenKind.APP,
                TokenSession.app(9L, "openid-user", mutableClock.instant())
        );
        mutableClock.advance(Duration.ofSeconds(6));

        assertAuthenticationRequired(() -> service.consumeRefreshToken(pair.refreshToken(), TokenKind.APP));
    }

    @Test
    void wrongKindAndMalformedRefreshTokensAreRejected() {
        TokenPair appPair = tokenService.issue(
                TokenKind.APP,
                TokenSession.app(9L, "openid-user", clock.instant())
        );
        TokenPair adminPair = tokenService.issue(
                TokenKind.ADMIN,
                TokenSession.admin(1L, "Super", List.of(), List.of(), clock.instant())
        );

        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(appPair.refreshToken(), TokenKind.ADMIN));
        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(adminPair.refreshToken(), TokenKind.APP));
        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken("not-a-token", TokenKind.APP));
        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(null, TokenKind.APP));
    }

    @Test
    void logoutRevokesAccessAndRefreshForTheSession() {
        TokenSession session = TokenSession.app(9L, "openid-user", clock.instant());
        TokenPair pair = tokenService.issue(TokenKind.APP, session);

        tokenService.revokeSession(session.sessionId(), TokenKind.APP);

        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).isEmpty();
        assertAuthenticationRequired(() -> tokenService.consumeRefreshToken(pair.refreshToken(), TokenKind.APP));
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

    private TokenProperties properties(Duration appAccessTtl, Duration appRefreshTtl) {
        return new TokenProperties(
                Duration.ofHours(2),
                Duration.ofDays(7),
                appAccessTtl,
                appRefreshTtl
        );
    }

    private static class RecordingTokenStore implements TokenStore {

        private final List<TokenGrant> savedGrants = new ArrayList<>();
        private int saveCalls;
        private String savedSessionId;

        @Override
        public void saveFamily(String sessionId, List<TokenGrant> grants) {
            saveCalls++;
            savedSessionId = sessionId;
            savedGrants.addAll(grants);
        }

        @Override
        public Optional<TokenSession> find(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<TokenSession> consumeRefreshAndRevokeFamily(String refreshKey, Duration revokedTtl) {
            return Optional.empty();
        }

        @Override
        public void revokeSession(String sessionId, Duration revokedTtl) {
        }

        @Override
        public boolean isSessionRevoked(String sessionId) {
            return false;
        }

        int saveCalls() {
            return saveCalls;
        }

        String savedSessionId() {
            return savedSessionId;
        }

        List<TokenGrant> savedGrants() {
            return List.copyOf(savedGrants);
        }
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
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
            return instant.get();
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }
    }
}

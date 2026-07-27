package org.muybaby.shopserver.auth.token;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryTokenStoreTest {

    private static final String GENERATION_ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test
    void saveFamilyMakesBothGrantsAvailable() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession session = appSession(clock, "session-1");

        store.saveFamily(session.sessionId(), List.of(
                grant("access-key", session, Duration.ofHours(1)),
                grant("refresh-key", session, Duration.ofDays(1))
        ));

        assertThat(store.find("access-key")).contains(session);
        assertThat(store.find("refresh-key")).contains(session);
    }

    @Test
    void saveFamilyDoesNotPartiallyWriteWhenLaterGrantExpiryOverflows() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession session = appSession(clock, "session-overflow");

        assertThatThrownBy(() -> store.saveFamily(session.sessionId(), List.of(
                grant("access-key", session, Duration.ofHours(1)),
                grant("refresh-key", session, Duration.ofSeconds(Long.MAX_VALUE))
        ))).isInstanceOf(ArithmeticException.class);

        assertThat(store.find("access-key")).isEmpty();
        assertThat(store.find("refresh-key")).isEmpty();
        assertThat(hasIndex(store, session.sessionId())).isFalse();
    }

    @Test
    void expiredSessionsAreNotReturned() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession session = appSession(clock, "session-expired");

        store.saveFamily(session.sessionId(), List.of(grant("expired-key", session, Duration.ZERO)));

        assertThat(store.find("expired-key")).isEmpty();
    }

    @Test
    void consumeRefreshReturnsSessionOnceMarksGenerationRevokedAndDeletesFamily() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession session = appSession(clock, "session-consume");
        store.saveFamily(session.sessionId(), List.of(
                grant("access-key", session, Duration.ofHours(1)),
                grant("refresh-key", session, Duration.ofDays(1))
        ));

        assertThat(store.consumeRefreshAndRevokeFamily("refresh-key", Duration.ofDays(1))).contains(session);

        assertThat(store.find("access-key")).isEmpty();
        assertThat(store.find("refresh-key")).isEmpty();
        assertThat(store.isSessionRevoked(session.sessionId())).isFalse();
        assertThat(store.isGenerationRevoked(session.generationId())).isTrue();
        assertThat(store.consumeRefreshAndRevokeFamily("refresh-key", Duration.ofDays(1))).isEmpty();
    }

    @Test
    void consumeRefreshRevokesOnlyThePresentedGeneration() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession session = new TokenSession(
                "family-consume",
                "11111111-1111-4111-8111-111111111111",
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                clock.instant()
        );
        store.saveFamily(session.sessionId(), List.of(
                grant("access-key", session, Duration.ofHours(1)),
                grant("refresh-key", session, Duration.ofDays(1))
        ));

        assertThat(store.consumeRefreshAndRevokeFamily("refresh-key", Duration.ofDays(1))).contains(session);

        assertThat(store.isSessionRevoked(session.sessionId())).isFalse();
        assertThat(store.isGenerationRevoked(session.generationId())).isTrue();
    }

    @Test
    void revokeSessionMarksRevokedAndDeletesIndexedFamily() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession session = appSession(clock, "session-logout");
        store.saveFamily(session.sessionId(), List.of(
                grant("access-key", session, Duration.ofHours(1)),
                grant("refresh-key", session, Duration.ofDays(1))
        ));

        store.revokeSession(session.sessionId(), Duration.ofDays(1));

        assertThat(store.isSessionRevoked(session.sessionId())).isTrue();
        assertThat(store.find("access-key")).isEmpty();
        assertThat(store.find("refresh-key")).isEmpty();
    }

    @Test
    void saveFamilyRejectsARefreshGenerationAfterLogoutWins() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession refreshed = new TokenSession(
                "family-logout-winner",
                "22222222-2222-4222-8222-222222222222",
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                clock.instant()
        );
        store.revokeSession(refreshed.sessionId(), Duration.ofDays(1));

        boolean saved = store.saveFamily(refreshed.sessionId(), List.of(
                grant("new-access-key", refreshed, Duration.ofHours(1)),
                grant("new-refresh-key", refreshed, Duration.ofDays(1))
        ));

        assertThat(saved).isFalse();
        assertThat(store.find("new-access-key")).isEmpty();
        assertThat(store.find("new-refresh-key")).isEmpty();
    }

    @Test
    void revokedMarkerExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T12:00:00Z"));
        InMemoryTokenStore store = new InMemoryTokenStore(clock);

        store.revokeSession("session-marker", Duration.ofSeconds(5));
        assertThat(store.isSessionRevoked("session-marker")).isTrue();

        clock.advance(Duration.ofSeconds(6));
        assertThat(store.isSessionRevoked("session-marker")).isFalse();
    }

    @Test
    void ordinaryOperationsSweepExpiredMarkersInBoundedBatches() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T12:00:00Z"));
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        for (int index = 0; index < 70; index++) {
            store.revokeSession("expired-family-" + index, Duration.ofSeconds(1));
            TokenSession session = new TokenSession(
                    "consume-family-" + index,
                    String.format("00000000-0000-0000-0000-%012x", index),
                    TokenKind.APP,
                    9L,
                    "openid",
                    List.of(),
                    List.of(),
                    clock.instant()
            );
            String refreshKey = "refresh-key-" + index;
            store.saveFamily(session.sessionId(), List.of(grant(
                    refreshKey,
                    session,
                    Duration.ofDays(1)
            )));
            store.consumeRefreshAndRevokeFamily(refreshKey, Duration.ofSeconds(1));
        }
        assertThat(markerCount(store)).isEqualTo(140);
        clock.advance(Duration.ofSeconds(2));

        store.find("missing-1");
        assertThat(markerCount(store)).isEqualTo(76);
        store.find("missing-2");
        assertThat(markerCount(store)).isEqualTo(12);
        store.find("missing-3");
        assertThat(markerCount(store)).isZero();
    }

    @Test
    void legacyFamilyWithoutIndexStillRejectsAccessAndDeletesPresentedRefresh() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession session = appSession(clock, "legacy-session");
        store.saveFamily(session.sessionId(), List.of(
                grant("legacy-access-key", session, Duration.ofHours(1)),
                grant("legacy-refresh-key", session, Duration.ofDays(1))
        ));
        removeIndex(store, session.sessionId());

        store.revokeSession(session.sessionId(), Duration.ofDays(1));

        assertThat(store.isSessionRevoked(session.sessionId())).isTrue();
        assertThat(store.find("legacy-access-key")).contains(session);
        assertThat(store.consumeRefreshAndRevokeFamily("legacy-refresh-key", Duration.ofDays(1))).isEmpty();
        assertThat(store.find("legacy-refresh-key")).isEmpty();
    }

    @Test
    void registeredLoginReplacesTheSameDeviceAndRevokesItsOldFamily() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T12:00:00Z"));
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession first = appSession(clock, "registered-first");
        store.saveRegisteredFamily(
                accountSession(clock, first, "device-1"),
                0,
                List.of(
                        grant("first-access", first, Duration.ofHours(1)),
                        grant("first-refresh", first, Duration.ofDays(1))
                ),
                Duration.ofDays(1)
        );
        clock.advance(Duration.ofMinutes(1));
        TokenSession second = appSession(clock, "registered-second");

        assertThat(store.saveRegisteredFamily(
                accountSession(clock, second, "device-1"),
                0,
                List.of(
                        grant("second-access", second, Duration.ofHours(1)),
                        grant("second-refresh", second, Duration.ofDays(1))
                ),
                Duration.ofDays(1)
        )).isTrue();

        assertThat(store.listSessions(TokenKind.APP, 9L))
                .extracting(AccountSession::sessionId)
                .containsExactly("registered-second");
        assertThat(store.isSessionRevoked("registered-first")).isTrue();
        assertThat(store.find("first-access")).isEmpty();
        assertThat(store.find("first-refresh")).isEmpty();
        assertThat(store.find("second-access")).contains(second);
    }

    @Test
    void registeredLoginAtomicallyEvictsTheOldestSessionAtTheLimit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T12:00:00Z"));
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        for (int index = 1; index <= 3; index++) {
            TokenSession session = appSession(clock, "limited-" + index);
            assertThat(store.saveRegisteredFamily(
                    accountSession(clock, session, "device-" + index),
                    2,
                    List.of(
                            grant("limited-access-" + index, session, Duration.ofHours(1)),
                            grant("limited-refresh-" + index, session, Duration.ofDays(1))
                    ),
                    Duration.ofDays(1)
            )).isTrue();
            clock.advance(Duration.ofMinutes(1));
        }

        assertThat(store.listSessions(TokenKind.APP, 9L))
                .extracting(AccountSession::sessionId)
                .containsExactly("limited-3", "limited-2");
        assertThat(store.isSessionRevoked("limited-1")).isTrue();
        assertThat(store.find("limited-access-1")).isEmpty();
        assertThat(store.find("limited-refresh-1")).isEmpty();
    }

    @Test
    void refreshConsumptionKeepsRegistrationAndTheNextGenerationPreservesLoginMetadata() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T12:00:00Z"));
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession first = appSession(clock, "rotating-session");
        AccountSession original = accountSession(clock, first, "device-1");
        store.saveRegisteredFamily(
                original,
                1,
                List.of(
                        grant("old-access", first, Duration.ofHours(1)),
                        grant("old-refresh", first, Duration.ofDays(1))
                ),
                Duration.ofDays(1)
        );

        assertThat(store.consumeRefreshAndRevokeFamily("old-refresh", Duration.ofDays(1)))
                .contains(first);
        assertThat(store.listSessions(TokenKind.APP, 9L)).containsExactly(original);

        clock.advance(Duration.ofHours(1));
        TokenSession next = new TokenSession(
                first.sessionId(),
                "22222222-2222-4222-8222-222222222222",
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                clock.instant()
        );
        AccountSession refreshObservation = new AccountSession(
                first.sessionId(),
                TokenKind.APP,
                9L,
                "openid",
                "",
                "203.0.113.8",
                "",
                clock.instant(),
                clock.instant()
        );
        store.saveRegisteredFamily(
                refreshObservation,
                1,
                List.of(
                        grant("new-access", next, Duration.ofHours(1)),
                        grant("new-refresh", next, Duration.ofDays(1))
                ),
                Duration.ofDays(1)
        );

        AccountSession refreshed = store.listSessions(TokenKind.APP, 9L).getFirst();
        assertThat(refreshed.sessionId()).isEqualTo(first.sessionId());
        assertThat(refreshed.deviceId()).isEqualTo("device-1");
        assertThat(refreshed.loginAt()).isEqualTo(original.loginAt());
        assertThat(refreshed.lastSeenAt()).isEqualTo(clock.instant());
        assertThat(refreshed.ipAddress()).isEqualTo("203.0.113.8");
        assertThat(store.find("new-access")).contains(next);
    }

    @Test
    void subjectRevokeTrimRenewAndTouchOperateOnlyOnOwnedRegistrations() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T12:00:00Z"));
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        for (int index = 1; index <= 3; index++) {
            TokenSession session = appSession(clock, "managed-" + index);
            store.saveRegisteredFamily(
                    accountSession(clock, session, "managed-device-" + index),
                    0,
                    List.of(grant("managed-token-" + index, session, Duration.ofHours(1))),
                    Duration.ofDays(1)
            );
            clock.advance(Duration.ofMinutes(1));
        }

        assertThat(store.trimSubjectSessions(TokenKind.APP, 9L, 2, Duration.ofDays(1)))
                .isEqualTo(1);
        assertThat(store.revokeSubjectSession(
                TokenKind.APP,
                10L,
                "managed-2",
                Duration.ofDays(1)
        )).isFalse();
        assertThat(store.touchSession(
                "managed-2",
                TokenKind.APP,
                Instant.parse("2026-07-06T13:00:00Z")
        )).isTrue();
        assertThat(store.renewSession("managed-2", TokenKind.APP, Duration.ofDays(2))).isTrue();
        assertThat(store.listSessions(TokenKind.APP, 9L))
                .filteredOn(session -> session.sessionId().equals("managed-2"))
                .singleElement()
                .extracting(AccountSession::lastSeenAt)
                .isEqualTo(Instant.parse("2026-07-06T13:00:00Z"));
        assertThat(store.revokeSubjectSessions(TokenKind.APP, 9L, Duration.ofDays(1)))
                .isEqualTo(2);
        assertThat(store.listSessions(TokenKind.APP, 9L)).isEmpty();
    }

    private TokenSession appSession(Clock clock, String sessionId) {
        return new TokenSession(
                sessionId,
                GENERATION_ID,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                clock.instant()
        );
    }

    private TokenGrant grant(String key, TokenSession session, Duration ttl) {
        return new TokenGrant(key, session, ttl);
    }

    private AccountSession accountSession(Clock clock, TokenSession session, String deviceId) {
        return new AccountSession(
                session.sessionId(),
                session.kind(),
                session.subjectId(),
                session.subjectName(),
                deviceId,
                "198.51.100.8",
                "test-agent",
                clock.instant(),
                clock.instant()
        );
    }

    @SuppressWarnings("unchecked")
    private void removeIndex(InMemoryTokenStore store, String sessionId) throws Exception {
        Field field = InMemoryTokenStore.class.getDeclaredField("sessionKeys");
        field.setAccessible(true);
        ((Map<String, ?>) field.get(store)).remove(sessionId);
    }

    private boolean hasIndex(InMemoryTokenStore store, String sessionId) throws Exception {
        Field field = InMemoryTokenStore.class.getDeclaredField("sessionKeys");
        field.setAccessible(true);
        return ((Map<String, ?>) field.get(store)).containsKey(sessionId);
    }

    private int markerCount(InMemoryTokenStore store) throws Exception {
        Field familyField = InMemoryTokenStore.class.getDeclaredField("revokedSessions");
        familyField.setAccessible(true);
        Field generationField = InMemoryTokenStore.class.getDeclaredField("revokedGenerations");
        generationField.setAccessible(true);
        return ((Map<?, ?>) familyField.get(store)).size()
                + ((Map<?, ?>) generationField.get(store)).size();
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

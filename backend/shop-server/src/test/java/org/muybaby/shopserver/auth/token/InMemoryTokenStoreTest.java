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
    void consumeRefreshReturnsSessionOnceMarksRevokedAndDeletesFamily() {
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
        assertThat(store.isSessionRevoked(session.sessionId())).isTrue();
        assertThat(store.consumeRefreshAndRevokeFamily("refresh-key", Duration.ofDays(1))).isEmpty();
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
    void revokedMarkerExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-06T12:00:00Z"));
        InMemoryTokenStore store = new InMemoryTokenStore(clock);

        store.revokeSession("session-marker", Duration.ofSeconds(5));
        assertThat(store.isSessionRevoked("session-marker")).isTrue();

        clock.advance(Duration.ofSeconds(6));
        assertThat(store.isSessionRevoked("session-marker")).isFalse();
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

    private TokenSession appSession(Clock clock, String sessionId) {
        return new TokenSession(sessionId, TokenKind.APP, 9L, "openid", List.of(), List.of(), clock.instant());
    }

    private TokenGrant grant(String key, TokenSession session, Duration ttl) {
        return new TokenGrant(key, session, ttl);
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

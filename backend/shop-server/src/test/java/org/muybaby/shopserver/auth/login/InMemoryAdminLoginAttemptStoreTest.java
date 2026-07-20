package org.muybaby.shopserver.auth.login;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryAdminLoginAttemptStoreTest {

    @Test
    void locksAtThresholdAndAutomaticallyExpires() {
        MutableClock clock = new MutableClock();
        InMemoryAdminLoginAttemptStore store = new InMemoryAdminLoginAttemptStore(properties(10), clock);

        assertThat(store.recordFailure("account", 2, Duration.ofMinutes(1), Duration.ofMinutes(5)))
                .isZero();
        assertThat(store.recordFailure("account", 2, Duration.ofMinutes(1), Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(store.lockedFor("account")).isEqualTo(Duration.ofMinutes(5));

        clock.advance(Duration.ofMinutes(5));

        assertThat(store.lockedFor("account")).isZero();
        assertThat(store.size()).isZero();
    }

    @Test
    void expiredFailureWindowStartsANewSequence() {
        MutableClock clock = new MutableClock();
        InMemoryAdminLoginAttemptStore store = new InMemoryAdminLoginAttemptStore(properties(10), clock);

        store.recordFailure("account", 2, Duration.ofMinutes(1), Duration.ofMinutes(5));
        clock.advance(Duration.ofMinutes(1));

        assertThat(store.recordFailure("account", 2, Duration.ofMinutes(1), Duration.ofMinutes(5)))
                .isZero();
    }

    @Test
    void refusesNewKeysAtCapacityButReclaimsExpiredEntries() {
        MutableClock clock = new MutableClock();
        InMemoryAdminLoginAttemptStore store = new InMemoryAdminLoginAttemptStore(properties(1), clock);
        store.recordFailure("first", 5, Duration.ofMinutes(1), Duration.ofMinutes(5));

        assertThatThrownBy(() -> store.recordFailure(
                "second", 5, Duration.ofMinutes(1), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class);

        clock.advance(Duration.ofMinutes(1));
        assertThat(store.recordFailure("second", 5, Duration.ofMinutes(1), Duration.ofMinutes(5)))
                .isZero();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void concurrentFailuresDoNotLoseTheThresholdTransition() throws Exception {
        MutableClock clock = new MutableClock();
        InMemoryAdminLoginAttemptStore store = new InMemoryAdminLoginAttemptStore(properties(10), clock);
        List<Callable<Duration>> failures = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            failures.add(() -> store.recordFailure(
                    "account", 100, Duration.ofMinutes(1), Duration.ofMinutes(5)));
        }

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Duration> decisions = executor.invokeAll(failures).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception ex) {
                            throw new AssertionError(ex);
                        }
                    })
                    .toList();
            assertThat(decisions).anyMatch(Duration::isPositive);
        }
        assertThat(store.lockedFor("account")).isEqualTo(Duration.ofMinutes(5));
    }

    private AdminLoginProtectionProperties properties(int maxEntries) {
        return new AdminLoginProtectionProperties(
                true,
                "memory",
                Duration.ofMinutes(15),
                5,
                12,
                30,
                Duration.ofMinutes(15),
                maxEntries);
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-07-19T00:00:00Z");

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
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}

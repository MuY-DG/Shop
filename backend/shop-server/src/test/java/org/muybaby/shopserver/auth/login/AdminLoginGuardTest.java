package org.muybaby.shopserver.auth.login;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminLoginGuardTest {

    @Test
    void normalizesAccountKeysAndNeverUsesRawIdentifiers() {
        RecordingStore store = new RecordingStore();
        AdminLoginGuard guard = new AdminLoginGuard(store, properties(true));

        AdminLoginAttempt first = guard.start("  Super  ", "2001:DB8::10");
        AdminLoginAttempt second = guard.start("super", "2001:db8::10");

        assertThat(first).isEqualTo(second);
        assertThat(List.of(first.pairKey(), first.accountKey(), first.ipKey()))
                .allSatisfy(key -> assertThat(key)
                        .doesNotContainIgnoringCase("Super")
                        .doesNotContainIgnoringCase("2001:db8"));
        assertThat(store.checkedKeys).hasSize(6);
    }

    @Test
    void canonicalAccountIdUsesOneLimitKeyForAllDatabaseEquivalentSpellings() {
        RecordingStore store = new RecordingStore();
        AdminLoginGuard guard = new AdminLoginGuard(store, properties(true));

        AdminLoginAttempt first = guard.start(42L, "198.51.100.8");
        AdminLoginAttempt second = guard.start(42L, "198.51.100.8");

        assertThat(first).isEqualTo(second);
        assertThat(first.accountKey()).doesNotEndWith(":42");
        assertThat(first.pairKey()).doesNotEndWith(":42");
    }

    @Test
    void recordsPairAccountAndIpFailuresAndLocksAtAnyDimension() {
        RecordingStore store = new RecordingStore();
        AdminLoginGuard guard = new AdminLoginGuard(store, properties(true));
        AdminLoginAttempt attempt = guard.start("operator", "198.51.100.8");
        store.failureDecisions.put(attempt.accountKey(), Duration.ofMinutes(15));

        assertThatThrownBy(() -> guard.recordFailure(attempt))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ADMIN_LOGIN_RATE_LIMITED));

        assertThat(store.failureCalls).extracting(FailureCall::key)
                .containsExactly(attempt.pairKey(), attempt.accountKey(), attempt.ipKey());
        assertThat(store.failureCalls).extracting(FailureCall::limit)
                .containsExactly(5, 12, 30);
    }

    @Test
    void accountAndIpDimensionsAggregateAcrossDifferentCounterparts() {
        AdminLoginProtectionProperties accountPolicy = properties(true, 100, 2, 100);
        InMemoryAdminLoginAttemptStore accountStore = new InMemoryAdminLoginAttemptStore(accountPolicy);
        AdminLoginGuard accountGuard = new AdminLoginGuard(accountStore, accountPolicy);
        accountGuard.recordFailure(accountGuard.start("operator", "198.51.100.1"));
        assertThatThrownBy(() -> accountGuard.recordFailure(
                accountGuard.start("operator", "198.51.100.2")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ADMIN_LOGIN_RATE_LIMITED));
        assertThatThrownBy(() -> accountGuard.start("operator", "198.51.100.3"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ADMIN_LOGIN_RATE_LIMITED));

        AdminLoginProtectionProperties ipPolicy = properties(true, 100, 100, 2);
        InMemoryAdminLoginAttemptStore ipStore = new InMemoryAdminLoginAttemptStore(ipPolicy);
        AdminLoginGuard ipGuard = new AdminLoginGuard(ipStore, ipPolicy);
        ipGuard.recordFailure(ipGuard.start("operator-a", "198.51.100.9"));
        assertThatThrownBy(() -> ipGuard.recordFailure(
                ipGuard.start("operator-b", "198.51.100.9")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ADMIN_LOGIN_RATE_LIMITED));
        assertThatThrownBy(() -> ipGuard.start("operator-c", "198.51.100.9"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ADMIN_LOGIN_RATE_LIMITED));
    }

    @Test
    void successfulLoginClearsAccountAndPairButPreservesIpPressure() {
        RecordingStore store = new RecordingStore();
        AdminLoginGuard guard = new AdminLoginGuard(store, properties(true));
        AdminLoginAttempt attempt = guard.start("operator", "198.51.100.8");

        guard.recordSuccess(attempt);

        assertThat(store.clearedKeys).containsExactly(attempt.accountKey(), attempt.pairKey());
        assertThat(store.clearedKeys).doesNotContain(attempt.ipKey());
    }

    @Test
    void storeFailureFailsClosedWithoutLeakingTheCause() {
        AdminLoginAttemptStore unavailable = new AdminLoginAttemptStore() {
            @Override
            public Duration lockedFor(String key) {
                throw new IllegalStateException("redis-secret-host");
            }

            @Override
            public Duration recordFailure(String key, int failureLimit, Duration failureWindow, Duration lockDuration) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void clear(String key) {
                throw new UnsupportedOperationException();
            }
        };
        AdminLoginGuard guard = new AdminLoginGuard(unavailable, properties(true));

        assertThatThrownBy(() -> guard.start("operator", "198.51.100.8"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE);
                    assertThat(ex.getMessage()).doesNotContain("redis-secret-host");
                });
    }

    @Test
    void disabledProtectionDoesNotTouchTheConfiguredStore() {
        RecordingStore store = new RecordingStore();
        AdminLoginGuard guard = new AdminLoginGuard(store, properties(false));

        AdminLoginAttempt attempt = guard.start("operator", "198.51.100.8");
        guard.recordFailure(attempt);
        guard.recordSuccess(attempt);

        assertThat(store.checkedKeys).isEmpty();
        assertThat(store.failureCalls).isEmpty();
        assertThat(store.clearedKeys).isEmpty();
    }

    private AdminLoginProtectionProperties properties(boolean enabled) {
        return properties(enabled, 5, 12, 30);
    }

    private AdminLoginProtectionProperties properties(
            boolean enabled,
            int pairFailureLimit,
            int accountFailureLimit,
            int ipFailureLimit
    ) {
        return new AdminLoginProtectionProperties(
                enabled,
                "memory",
                Duration.ofMinutes(15),
                pairFailureLimit,
                accountFailureLimit,
                ipFailureLimit,
                Duration.ofMinutes(15),
                100);
    }

    private static final class RecordingStore implements AdminLoginAttemptStore {

        private final List<String> checkedKeys = new ArrayList<>();
        private final List<FailureCall> failureCalls = new ArrayList<>();
        private final List<String> clearedKeys = new ArrayList<>();
        private final Map<String, Duration> lockDecisions = new HashMap<>();
        private final Map<String, Duration> failureDecisions = new HashMap<>();

        @Override
        public Duration lockedFor(String key) {
            checkedKeys.add(key);
            return lockDecisions.getOrDefault(key, Duration.ZERO);
        }

        @Override
        public Duration recordFailure(String key, int failureLimit, Duration failureWindow, Duration lockDuration) {
            failureCalls.add(new FailureCall(key, failureLimit));
            return failureDecisions.getOrDefault(key, Duration.ZERO);
        }

        @Override
        public void clear(String key) {
            clearedKeys.add(key);
        }
    }

    private record FailureCall(String key, int limit) {
    }
}

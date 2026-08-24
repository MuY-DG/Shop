package org.muybaby.shopserver.auth.login;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
@Profile({"local", "test"})
@ConditionalOnProperty(
        prefix = "shop.auth.admin-login-protection",
        name = "store",
        havingValue = "memory"
)
public class InMemoryAdminLoginAttemptStore implements AdminLoginAttemptStore {

    private final Map<String, State> states = new HashMap<>();
    private final Object monitor = new Object();
    private final int maxEntries;
    private final Clock clock;

    @Autowired
    public InMemoryAdminLoginAttemptStore(AdminLoginProtectionProperties properties) {
        this(properties, Clock.systemUTC());
    }

    InMemoryAdminLoginAttemptStore(AdminLoginProtectionProperties properties, Clock clock) {
        this.maxEntries = properties.effectiveMemoryMaxEntries();
        this.clock = clock;
    }

    @Override
    public Duration lockedFor(String key) {
        synchronized (monitor) {
            long now = clock.millis();
            State state = states.get(key);
            if (state == null) {
                return Duration.ZERO;
            }
            if (state.lockedUntilMillis() > now) {
                return Duration.ofMillis(state.lockedUntilMillis() - now);
            }
            if (state.failureWindowEndsAtMillis() <= now) {
                states.remove(key);
            }
            return Duration.ZERO;
        }
    }

    @Override
    public Duration recordFailure(
            String key,
            int failureLimit,
            Duration failureWindow,
            Duration lockDuration
    ) {
        synchronized (monitor) {
            long now = clock.millis();
            State existing = states.get(key);
            if (existing != null && existing.lockedUntilMillis() > now) {
                return Duration.ofMillis(existing.lockedUntilMillis() - now);
            }
            if (existing == null) {
                ensureCapacity(now);
            }

            int failures = existing == null || existing.failureWindowEndsAtMillis() <= now
                    ? 1
                    : existing.failures() + 1;
            if (failures >= failureLimit) {
                long lockedUntil = addClamped(now, atLeastOneMillis(lockDuration));
                states.put(key, new State(failures, lockedUntil, lockedUntil));
                return Duration.ofMillis(lockedUntil - now);
            }

            long windowEndsAt = existing == null || existing.failureWindowEndsAtMillis() <= now
                    ? addClamped(now, atLeastOneMillis(failureWindow))
                    : existing.failureWindowEndsAtMillis();
            states.put(key, new State(failures, windowEndsAt, 0L));
            return Duration.ZERO;
        }
    }

    @Override
    public void clear(String key) {
        synchronized (monitor) {
            states.remove(key);
        }
    }

    int size() {
        synchronized (monitor) {
            return states.size();
        }
    }

    private void ensureCapacity(long now) {
        if (states.size() < maxEntries) {
            return;
        }
        states.entrySet().removeIf(entry -> entry.getValue().expiresAtOrBefore(now));
        if (states.size() >= maxEntries) {
            throw new IllegalStateException("In-memory admin login attempt store is full");
        }
    }

    private long atLeastOneMillis(Duration duration) {
        return Math.max(1L, duration.toMillis());
    }

    private long addClamped(long value, long increment) {
        if (Long.MAX_VALUE - value < increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    private record State(int failures, long failureWindowEndsAtMillis, long lockedUntilMillis) {

        private boolean expiresAtOrBefore(long now) {
            return failureWindowEndsAtMillis <= now && lockedUntilMillis <= now;
        }
    }
}

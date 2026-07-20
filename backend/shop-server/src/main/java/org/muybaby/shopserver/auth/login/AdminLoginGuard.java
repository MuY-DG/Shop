package org.muybaby.shopserver.auth.login;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.error.RateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AdminLoginGuard {

    private static final Logger log = LoggerFactory.getLogger(AdminLoginGuard.class);
    private static final String KEY_PREFIX = "shop:auth:admin-login:";
    private static final long STORE_FAILURE_LOG_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();

    private final AdminLoginAttemptStore attemptStore;
    private final AdminLoginProtectionProperties properties;
    private final AtomicLong lastStoreFailureLogAt = new AtomicLong(0L);

    public AdminLoginGuard(
            AdminLoginAttemptStore attemptStore,
            AdminLoginProtectionProperties properties
    ) {
        this.attemptStore = attemptStore;
        this.properties = properties;
    }

    public AdminLoginAttempt start(String username, String clientIp) {
        return startAttempt(normalizeAccount(username), clientIp);
    }

    public AdminLoginAttempt start(long adminUserId, String clientIp) {
        if (adminUserId < 1) {
            throw new IllegalArgumentException("Admin user id must be positive");
        }
        return startAttempt("id:" + adminUserId, clientIp);
    }

    private AdminLoginAttempt startAttempt(String accountIdentity, String clientIp) {
        AdminLoginAttempt attempt = attempt(accountIdentity, clientIp);
        if (!properties.isEnabled()) {
            return attempt;
        }

        Duration retryAfter;
        try {
            retryAfter = maximum(List.of(
                    attemptStore.lockedFor(attempt.pairKey()),
                    attemptStore.lockedFor(attempt.accountKey()),
                    attemptStore.lockedFor(attempt.ipKey())));
        } catch (RuntimeException ex) {
            throw unavailable(ex);
        }
        if (retryAfter.isPositive()) {
            throw rateLimited(retryAfter);
        }
        return attempt;
    }

    public void recordFailure(AdminLoginAttempt attempt) {
        if (!properties.isEnabled()) {
            return;
        }

        Duration retryAfter;
        try {
            retryAfter = maximum(List.of(
                    attemptStore.recordFailure(
                            attempt.pairKey(),
                            properties.effectivePairFailureLimit(),
                            properties.effectiveFailureWindow(),
                            properties.effectiveLockDuration()),
                    attemptStore.recordFailure(
                            attempt.accountKey(),
                            properties.effectiveAccountFailureLimit(),
                            properties.effectiveFailureWindow(),
                            properties.effectiveLockDuration()),
                    attemptStore.recordFailure(
                            attempt.ipKey(),
                            properties.effectiveIpFailureLimit(),
                            properties.effectiveFailureWindow(),
                            properties.effectiveLockDuration())));
        } catch (RuntimeException ex) {
            throw unavailable(ex);
        }
        if (retryAfter.isPositive()) {
            throw rateLimited(retryAfter);
        }
    }

    public void recordSuccess(AdminLoginAttempt attempt) {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            attemptStore.clear(attempt.accountKey());
            attemptStore.clear(attempt.pairKey());
        } catch (RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    private AdminLoginAttempt attempt(String username, String clientIp) {
        String accountHash = sha256(username);
        String ipHash = sha256(normalizeIp(clientIp));
        return new AdminLoginAttempt(
                KEY_PREFIX + "pair:" + sha256(accountHash + ':' + ipHash),
                KEY_PREFIX + "account:" + accountHash,
                KEY_PREFIX + "ip:" + ipHash);
    }

    private String normalizeAccount(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    private String normalizeIp(String clientIp) {
        return clientIp == null || clientIp.isBlank()
                ? "unknown"
                : clientIp.strip().toLowerCase(Locale.ROOT);
    }

    private Duration maximum(List<Duration> values) {
        return values.stream()
                .filter(Duration::isPositive)
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
    }

    private BusinessException unavailable(RuntimeException ex) {
        long now = System.currentTimeMillis();
        long previous = lastStoreFailureLogAt.get();
        if (now - previous >= STORE_FAILURE_LOG_INTERVAL_MILLIS
                && lastStoreFailureLogAt.compareAndSet(previous, now)) {
            log.error("Admin login protection store is unavailable; rejecting login", ex);
        }
        return new BusinessException(ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE);
    }

    private RateLimitException rateLimited(Duration retryAfter) {
        long millis = Math.max(1L, retryAfter.toMillis());
        long seconds = millis > Long.MAX_VALUE - 999L
                ? Long.MAX_VALUE / 1_000L
                : (millis + 999L) / 1_000L;
        return new RateLimitException(ErrorCode.ADMIN_LOGIN_RATE_LIMITED, seconds);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

package org.muybaby.shopserver.auth.login;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.auth.admin-login-protection")
public record AdminLoginProtectionProperties(
        Boolean enabled,
        String store,
        Duration failureWindow,
        Integer pairFailureLimit,
        Integer accountFailureLimit,
        Integer ipFailureLimit,
        Duration lockDuration,
        Integer memoryMaxEntries
) {

    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    public Duration effectiveFailureWindow() {
        return positiveOrDefault(failureWindow, Duration.ofMinutes(15));
    }

    public int effectivePairFailureLimit() {
        return positiveOrDefault(pairFailureLimit, 5);
    }

    public int effectiveAccountFailureLimit() {
        return positiveOrDefault(accountFailureLimit, 12);
    }

    public int effectiveIpFailureLimit() {
        return positiveOrDefault(ipFailureLimit, 30);
    }

    public Duration effectiveLockDuration() {
        return positiveOrDefault(lockDuration, Duration.ofMinutes(15));
    }

    public int effectiveMemoryMaxEntries() {
        return positiveOrDefault(memoryMaxEntries, 100_000);
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }
}

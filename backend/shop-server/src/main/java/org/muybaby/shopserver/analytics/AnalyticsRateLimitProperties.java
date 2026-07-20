package org.muybaby.shopserver.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
@ConfigurationProperties(prefix = "shop.analytics.rate-limit")
public record AnalyticsRateLimitProperties(
        Boolean enabled,
        Duration window,
        Integer ipEventLimit,
        Integer visitorEventLimit
) {
    public boolean isEnabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    public Duration effectiveWindow() {
        return window == null || window.isZero() || window.isNegative() ? Duration.ofMinutes(1) : window;
    }

    public int effectiveIpEventLimit() {
        return ipEventLimit == null || ipEventLimit < 1 ? 5_000 : ipEventLimit;
    }

    public int effectiveVisitorEventLimit() {
        return visitorEventLimit == null || visitorEventLimit < 1 ? 1_000 : visitorEventLimit;
    }

}

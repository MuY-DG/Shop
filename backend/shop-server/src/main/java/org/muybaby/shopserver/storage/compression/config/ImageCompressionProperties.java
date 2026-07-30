package org.muybaby.shopserver.storage.compression.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.storage.image-compression")
public record ImageCompressionProperties(
        Boolean requestedEnabled,
        ImageCompressionConfigSource configSource,
        String apiKey,
        Integer monthlyLimit,
        Duration requestTimeout,
        Integer maxAttempts,
        Duration retryDelay
) {

    public boolean effectiveRequestedEnabled() {
        return requestedEnabled == null || requestedEnabled;
    }

    public ImageCompressionConfigSource effectiveConfigSource() {
        return configSource == null ? ImageCompressionConfigSource.AUTO : configSource;
    }

    public int effectiveMonthlyLimit() {
        return monthlyLimit == null ? 500 : monthlyLimit;
    }

    public Duration effectiveRequestTimeout() {
        return requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
    }

    public int effectiveMaxAttempts() {
        return maxAttempts == null ? 2 : maxAttempts;
    }

    public Duration effectiveRetryDelay() {
        return retryDelay == null ? Duration.ofSeconds(1) : retryDelay;
    }
}

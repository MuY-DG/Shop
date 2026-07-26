package org.muybaby.shopserver.storage.compression.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.storage.image-compression")
public record ImageCompressionProperties(
        Boolean requestedEnabled,
        ImageCompressionConfigSource configSource,
        String apiKey,
        Integer monthlyLimit
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
}

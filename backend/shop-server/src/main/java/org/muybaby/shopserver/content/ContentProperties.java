package org.muybaby.shopserver.content;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.content")
public record ContentProperties(
        Boolean cacheEnabled,
        Duration homeCacheTtl,
        Duration contactCacheTtl
) {
    public ContentProperties {
        cacheEnabled = cacheEnabled == null || cacheEnabled;
        homeCacheTtl = positiveOrDefault(homeCacheTtl, Duration.ofHours(6));
        contactCacheTtl = positiveOrDefault(contactCacheTtl, Duration.ofHours(24));
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}

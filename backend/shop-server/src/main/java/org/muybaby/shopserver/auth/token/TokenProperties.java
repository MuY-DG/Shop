package org.muybaby.shopserver.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "shop.auth")
public record TokenProperties(
        Duration adminAccessTtl,
        Duration adminRefreshTtl,
        Duration appAccessTtl,
        Duration appRefreshTtl
) {
    public TokenProperties {
        adminAccessTtl = requirePositive("adminAccessTtl", adminAccessTtl);
        adminRefreshTtl = requirePositive("adminRefreshTtl", adminRefreshTtl);
        appAccessTtl = requirePositive("appAccessTtl", appAccessTtl);
        appRefreshTtl = requirePositive("appRefreshTtl", appRefreshTtl);
    }

    private static Duration requirePositive(String name, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return ttl;
    }
}

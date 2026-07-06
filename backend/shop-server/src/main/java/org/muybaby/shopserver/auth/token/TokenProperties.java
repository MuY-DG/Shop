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
}

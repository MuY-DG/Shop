package org.muybaby.shopserver.auth.token;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "shop.auth")
public record TokenProperties(
        @Value("${shop.auth.admin-access-ttl}")
        Duration adminAccessTtl,
        @Value("${shop.auth.admin-refresh-ttl}")
        Duration adminRefreshTtl,
        @Value("${shop.auth.app-access-ttl}")
        Duration appAccessTtl,
        @Value("${shop.auth.app-refresh-ttl}")
        Duration appRefreshTtl
) {
}

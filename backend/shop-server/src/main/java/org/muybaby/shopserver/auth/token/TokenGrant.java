package org.muybaby.shopserver.auth.token;

import java.time.Duration;
import java.util.Objects;

public record TokenGrant(String key, TokenSession session, Duration ttl) {
    public TokenGrant {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Token grant key is required");
        }
        Objects.requireNonNull(session, "Token grant session is required");
        Objects.requireNonNull(ttl, "Token grant TTL is required");
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("Token grant TTL cannot be negative");
        }
    }
}

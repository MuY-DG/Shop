package org.muybaby.shopserver.auth.token;

import java.time.Duration;
import java.util.Optional;

public interface TokenStore {
    void save(String key, TokenSession session, Duration ttl);

    Optional<TokenSession> find(String key);

    void delete(String key);
}

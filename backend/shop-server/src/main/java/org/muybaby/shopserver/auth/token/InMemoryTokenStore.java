package org.muybaby.shopserver.auth.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "memory")
public class InMemoryTokenStore implements TokenStore {

    private final Clock clock;
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();

    public InMemoryTokenStore() {
        this(Clock.systemUTC());
    }

    public InMemoryTokenStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void save(String key, TokenSession session, Duration ttl) {
        sessions.put(key, new StoredSession(session, clock.instant().plus(ttl)));
    }

    @Override
    public Optional<TokenSession> find(String key) {
        StoredSession stored = sessions.get(key);
        if (stored == null || !stored.expiresAt().isAfter(clock.instant())) {
            sessions.remove(key);
            return Optional.empty();
        }
        return Optional.of(stored.session());
    }

    @Override
    public void delete(String key) {
        sessions.remove(key);
    }

    private record StoredSession(TokenSession session, Instant expiresAt) {
    }
}

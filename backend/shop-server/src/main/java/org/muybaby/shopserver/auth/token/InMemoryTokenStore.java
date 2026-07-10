package org.muybaby.shopserver.auth.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "memory")
public class InMemoryTokenStore implements TokenStore {

    private final Clock clock;
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionKeys = new ConcurrentHashMap<>();
    private final Map<String, Instant> revokedSessions = new ConcurrentHashMap<>();

    public InMemoryTokenStore() {
        this(Clock.systemUTC());
    }

    public InMemoryTokenStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void saveFamily(String sessionId, List<TokenGrant> grants) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID is required");
        }
        List<TokenGrant> family = List.copyOf(grants);
        if (family.isEmpty()) {
            throw new IllegalArgumentException("At least one token grant is required");
        }
        Instant now = clock.instant();
        Map<String, StoredSession> preparedSessions = new LinkedHashMap<>();
        Set<String> preparedKeys = ConcurrentHashMap.newKeySet();
        for (TokenGrant grant : family) {
            if (!sessionId.equals(grant.session().sessionId())) {
                throw new IllegalArgumentException("Token grant session does not match family session");
            }
            preparedSessions.put(grant.key(), new StoredSession(grant.session(), now.plus(grant.ttl())));
            preparedKeys.add(grant.key());
        }

        sessions.putAll(preparedSessions);
        Set<String> keys = sessionKeys.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet());
        keys.addAll(preparedKeys);
    }

    @Override
    public synchronized Optional<TokenSession> find(String key) {
        StoredSession stored = sessions.get(key);
        if (stored == null) {
            return Optional.empty();
        }
        if (!stored.expiresAt().isAfter(clock.instant())) {
            removeToken(key, stored.session().sessionId());
            return Optional.empty();
        }
        return Optional.of(stored.session());
    }

    @Override
    public synchronized Optional<TokenSession> consumeRefreshAndRevokeFamily(String refreshKey, Duration revokedTtl) {
        StoredSession stored = sessions.get(refreshKey);
        if (stored == null) {
            return Optional.empty();
        }
        if (!stored.expiresAt().isAfter(clock.instant())) {
            removeToken(refreshKey, stored.session().sessionId());
            return Optional.empty();
        }

        String sessionId = stored.session().sessionId();
        if (isSessionRevokedInternal(sessionId)) {
            removeToken(refreshKey, sessionId);
            return Optional.empty();
        }

        revokedSessions.put(sessionId, clock.instant().plus(revokedTtl));
        removeFamily(sessionId);
        sessions.remove(refreshKey);
        return Optional.of(stored.session());
    }

    @Override
    public synchronized void revokeSession(String sessionId, Duration revokedTtl) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        revokedSessions.put(sessionId, clock.instant().plus(revokedTtl));
        removeFamily(sessionId);
    }

    @Override
    public synchronized boolean isSessionRevoked(String sessionId) {
        return isSessionRevokedInternal(sessionId);
    }

    private boolean isSessionRevokedInternal(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Instant expiresAt = revokedSessions.get(sessionId);
        if (expiresAt == null) {
            return false;
        }
        if (!expiresAt.isAfter(clock.instant())) {
            revokedSessions.remove(sessionId, expiresAt);
            return false;
        }
        return true;
    }

    private void removeFamily(String sessionId) {
        Set<String> keys = sessionKeys.remove(sessionId);
        if (keys != null) {
            keys.forEach(sessions::remove);
        }
    }

    private void removeToken(String key, String sessionId) {
        sessions.remove(key);
        Set<String> keys = sessionKeys.get(sessionId);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                sessionKeys.remove(sessionId, keys);
            }
        }
    }

    private record StoredSession(TokenSession session, Instant expiresAt) {
    }
}

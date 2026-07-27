package org.muybaby.shopserver.auth.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "shop.auth.token-store", havingValue = "memory")
public class InMemoryTokenStore implements TokenStore {

    private static final int MARKER_CLEANUP_LIMIT = 64;

    private final Clock clock;
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionKeys = new ConcurrentHashMap<>();
    private final Map<String, Instant> revokedSessions = new ConcurrentHashMap<>();
    private final Map<String, Instant> revokedGenerations = new ConcurrentHashMap<>();
    private final Map<String, StoredAccountSession> accountSessions = new ConcurrentHashMap<>();
    private final Map<AccountSubject, Set<String>> subjectSessionIds = new ConcurrentHashMap<>();
    private final PriorityQueue<MarkerExpiry> markerExpiries = new PriorityQueue<>();

    public InMemoryTokenStore() {
        this(Clock.systemUTC());
    }

    public InMemoryTokenStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized boolean saveFamily(String sessionId, List<TokenGrant> grants) {
        sweepExpiredMarkers();
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
        if (isSessionRevokedInternal(sessionId)) {
            return false;
        }

        sessions.putAll(preparedSessions);
        Set<String> keys = sessionKeys.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet());
        keys.addAll(preparedKeys);
        return true;
    }

    @Override
    public synchronized boolean saveRegisteredFamily(
            AccountSession accountSession,
            int maxSessions,
            List<TokenGrant> grants,
            Duration revokedTtl
    ) {
        sweepExpiredMarkers();
        sweepExpiredAccountSessions();
        if (maxSessions < 0) {
            throw new IllegalArgumentException("Maximum sessions cannot be negative");
        }
        List<TokenGrant> family = prepareFamily(accountSession, grants);
        Instant now = clock.instant();
        Map<String, StoredSession> preparedSessions = new LinkedHashMap<>();
        Set<String> preparedKeys = ConcurrentHashMap.newKeySet();
        Instant metadataExpiresAt = now.plusMillis(1);
        for (TokenGrant grant : family) {
            Instant expiresAt = now.plus(grant.ttl());
            preparedSessions.put(grant.key(), new StoredSession(grant.session(), expiresAt));
            preparedKeys.add(grant.key());
            if (expiresAt.isAfter(metadataExpiresAt)) {
                metadataExpiresAt = expiresAt;
            }
        }
        if (isSessionRevokedInternal(accountSession.sessionId())) {
            return false;
        }

        AccountSubject subject = subject(accountSession.kind(), accountSession.subjectId());
        Set<String> ids = subjectSessionIds.computeIfAbsent(
                subject,
                ignored -> ConcurrentHashMap.newKeySet()
        );
        StoredAccountSession existing = accountSessions.get(accountSession.sessionId());
        validateExistingRegistration(existing, accountSession);

        if (!accountSession.deviceId().isBlank()) {
            List<String> sameDeviceSessions = ids.stream()
                    .filter(sessionId -> !sessionId.equals(accountSession.sessionId()))
                    .filter(sessionId -> {
                        StoredAccountSession stored = accountSessions.get(sessionId);
                        return stored != null
                                && accountSession.deviceId().equals(stored.session().deviceId());
                    })
                    .toList();
            sameDeviceSessions.forEach(sessionId -> revokeSessionInternal(sessionId, revokedTtl));
        }

        ids = subjectSessionIds.computeIfAbsent(subject, ignored -> ConcurrentHashMap.newKeySet());
        boolean indexed = ids.contains(accountSession.sessionId());
        if (!indexed && maxSessions > 0) {
            while (ids.size() >= maxSessions) {
                String oldestSessionId = oldestSessionId(ids);
                if (oldestSessionId == null) {
                    break;
                }
                revokeSessionInternal(oldestSessionId, revokedTtl);
                ids = subjectSessionIds.computeIfAbsent(
                        subject,
                        ignored -> ConcurrentHashMap.newKeySet()
                );
            }
        }

        sessions.putAll(preparedSessions);
        Set<String> keys = sessionKeys.computeIfAbsent(
                accountSession.sessionId(),
                ignored -> ConcurrentHashMap.newKeySet()
        );
        keys.addAll(preparedKeys);
        AccountSession merged = mergeAccountSession(existing, accountSession);
        accountSessions.put(
                accountSession.sessionId(),
                new StoredAccountSession(merged, metadataExpiresAt)
        );
        subjectSessionIds.computeIfAbsent(subject, ignored -> ConcurrentHashMap.newKeySet())
                .add(accountSession.sessionId());
        return true;
    }

    @Override
    public synchronized Optional<TokenSession> find(String key) {
        sweepExpiredMarkers();
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
        sweepExpiredMarkers();
        StoredSession stored = sessions.get(refreshKey);
        if (stored == null) {
            return Optional.empty();
        }
        if (!stored.expiresAt().isAfter(clock.instant())) {
            removeToken(refreshKey, stored.session().sessionId());
            return Optional.empty();
        }

        String sessionId = stored.session().sessionId();
        String generationId = stored.session().generationId();
        if (isSessionRevokedInternal(sessionId) || isGenerationRevokedInternal(generationId)) {
            removeToken(refreshKey, sessionId);
            return Optional.empty();
        }

        putMarker(revokedGenerations, generationId, revokedTtl, MarkerType.GENERATION);
        removeFamily(sessionId);
        sessions.remove(refreshKey);
        return Optional.of(stored.session());
    }

    @Override
    public synchronized void revokeSession(String sessionId, Duration revokedTtl) {
        sweepExpiredMarkers();
        sweepExpiredAccountSessions();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        revokeSessionInternal(sessionId, revokedTtl);
    }

    @Override
    public synchronized List<AccountSession> listSessions(TokenKind kind, Long subjectId) {
        sweepExpiredMarkers();
        sweepExpiredAccountSessions();
        AccountSubject subject = subject(kind, subjectId);
        Set<String> ids = subjectSessionIds.get(subject);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(accountSessions::get)
                .filter(stored -> stored != null && stored.expiresAt().isAfter(clock.instant()))
                .map(StoredAccountSession::session)
                .filter(session -> session.kind() == kind && subjectId.equals(session.subjectId()))
                .sorted(Comparator.comparing(AccountSession::loginAt)
                        .reversed()
                        .thenComparing(AccountSession::sessionId))
                .toList();
    }

    @Override
    public synchronized boolean revokeSubjectSession(
            TokenKind kind,
            Long subjectId,
            String sessionId,
            Duration revokedTtl
    ) {
        sweepExpiredMarkers();
        sweepExpiredAccountSessions();
        StoredAccountSession stored = accountSessions.get(sessionId);
        if (stored == null
                || stored.session().kind() != kind
                || !subjectId.equals(stored.session().subjectId())) {
            return false;
        }
        revokeSessionInternal(sessionId, revokedTtl);
        return true;
    }

    @Override
    public synchronized int revokeSubjectSessions(
            TokenKind kind,
            Long subjectId,
            Duration revokedTtl
    ) {
        sweepExpiredMarkers();
        sweepExpiredAccountSessions();
        AccountSubject subject = subject(kind, subjectId);
        Set<String> ids = subjectSessionIds.get(subject);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<String> ownedSessionIds = ids.stream()
                .filter(sessionId -> {
                    StoredAccountSession stored = accountSessions.get(sessionId);
                    return stored != null
                            && stored.session().kind() == kind
                            && subjectId.equals(stored.session().subjectId());
                })
                .toList();
        ownedSessionIds.forEach(sessionId -> revokeSessionInternal(sessionId, revokedTtl));
        return ownedSessionIds.size();
    }

    @Override
    public synchronized int trimSubjectSessions(
            TokenKind kind,
            Long subjectId,
            int maxSessions,
            Duration revokedTtl
    ) {
        sweepExpiredMarkers();
        sweepExpiredAccountSessions();
        if (maxSessions < 0) {
            throw new IllegalArgumentException("Maximum sessions cannot be negative");
        }
        if (maxSessions == 0) {
            return 0;
        }
        AccountSubject subject = subject(kind, subjectId);
        Set<String> ids = subjectSessionIds.get(subject);
        int revoked = 0;
        while (ids != null && ids.size() > maxSessions) {
            String oldestSessionId = oldestSessionId(ids);
            if (oldestSessionId == null) {
                break;
            }
            revokeSessionInternal(oldestSessionId, revokedTtl);
            revoked++;
            ids = subjectSessionIds.get(subject);
        }
        return revoked;
    }

    @Override
    public synchronized boolean renewSession(String sessionId, TokenKind kind, Duration ttl) {
        sweepExpiredMarkers();
        sweepExpiredAccountSessions();
        StoredAccountSession stored = accountSessions.get(sessionId);
        if (stored == null || stored.session().kind() != kind) {
            return false;
        }
        Instant renewedExpiry = clock.instant().plus(ttl);
        if (renewedExpiry.isAfter(stored.expiresAt())) {
            accountSessions.put(sessionId, new StoredAccountSession(stored.session(), renewedExpiry));
        }
        return true;
    }

    @Override
    public synchronized boolean touchSession(
            String sessionId,
            TokenKind kind,
            Instant lastSeenAt
    ) {
        sweepExpiredMarkers();
        sweepExpiredAccountSessions();
        StoredAccountSession stored = accountSessions.get(sessionId);
        if (stored == null || stored.session().kind() != kind) {
            return false;
        }
        AccountSession session = stored.session();
        AccountSession touched = new AccountSession(
                session.sessionId(),
                session.kind(),
                session.subjectId(),
                session.subjectName(),
                session.deviceId(),
                session.ipAddress(),
                session.userAgent(),
                session.loginAt(),
                lastSeenAt
        );
        accountSessions.put(sessionId, new StoredAccountSession(touched, stored.expiresAt()));
        return true;
    }

    @Override
    public synchronized boolean isSessionRevoked(String sessionId) {
        sweepExpiredMarkers();
        return isSessionRevokedInternal(sessionId);
    }

    @Override
    public synchronized boolean isGenerationRevoked(String generationId) {
        sweepExpiredMarkers();
        return isGenerationRevokedInternal(generationId);
    }

    private void putMarker(
            Map<String, Instant> markers,
            String id,
            Duration ttl,
            MarkerType markerType
    ) {
        Instant expiresAt = clock.instant().plus(ttl);
        markers.put(id, expiresAt);
        markerExpiries.add(new MarkerExpiry(markerType, id, expiresAt));
    }

    private void sweepExpiredMarkers() {
        Instant now = clock.instant();
        for (int count = 0; count < MARKER_CLEANUP_LIMIT; count++) {
            MarkerExpiry expiry = markerExpiries.peek();
            if (expiry == null || expiry.expiresAt().isAfter(now)) {
                return;
            }
            markerExpiries.remove();
            Map<String, Instant> markers = expiry.type() == MarkerType.FAMILY
                    ? revokedSessions
                    : revokedGenerations;
            markers.remove(expiry.id(), expiry.expiresAt());
        }
    }

    private void sweepExpiredAccountSessions() {
        Instant now = clock.instant();
        List<String> expiredSessionIds = accountSessions.entrySet().stream()
                .filter(entry -> !entry.getValue().expiresAt().isAfter(now))
                .map(Map.Entry::getKey)
                .toList();
        expiredSessionIds.forEach(this::removeAccountSession);
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

    private boolean isGenerationRevokedInternal(String generationId) {
        if (generationId == null || generationId.isBlank()) {
            return false;
        }
        Instant expiresAt = revokedGenerations.get(generationId);
        if (expiresAt == null) {
            return false;
        }
        if (!expiresAt.isAfter(clock.instant())) {
            revokedGenerations.remove(generationId, expiresAt);
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

    private List<TokenGrant> prepareFamily(AccountSession accountSession, List<TokenGrant> grants) {
        if (accountSession == null) {
            throw new IllegalArgumentException("Account session is required");
        }
        List<TokenGrant> family = List.copyOf(grants);
        if (family.isEmpty()) {
            throw new IllegalArgumentException("At least one token grant is required");
        }
        for (TokenGrant grant : family) {
            TokenSession session = grant.session();
            if (!accountSession.sessionId().equals(session.sessionId())
                    || accountSession.kind() != session.kind()
                    || !accountSession.subjectId().equals(session.subjectId())) {
                throw new IllegalArgumentException(
                        "Token grant session does not match account session registration"
                );
            }
        }
        return family;
    }

    private void validateExistingRegistration(
            StoredAccountSession existing,
            AccountSession accountSession
    ) {
        if (existing == null) {
            return;
        }
        AccountSession stored = existing.session();
        if (stored.kind() != accountSession.kind()
                || !stored.subjectId().equals(accountSession.subjectId())) {
            throw new IllegalArgumentException("Account session ownership cannot change");
        }
    }

    private AccountSession mergeAccountSession(
            StoredAccountSession existing,
            AccountSession incoming
    ) {
        if (existing == null) {
            return incoming;
        }
        AccountSession stored = existing.session();
        return new AccountSession(
                incoming.sessionId(),
                incoming.kind(),
                incoming.subjectId(),
                incoming.subjectName().isBlank() ? stored.subjectName() : incoming.subjectName(),
                incoming.deviceId().isBlank() ? stored.deviceId() : incoming.deviceId(),
                incoming.ipAddress().isBlank() ? stored.ipAddress() : incoming.ipAddress(),
                incoming.userAgent().isBlank() ? stored.userAgent() : incoming.userAgent(),
                stored.loginAt(),
                incoming.lastSeenAt()
        );
    }

    private void revokeSessionInternal(String sessionId, Duration revokedTtl) {
        putMarker(revokedSessions, sessionId, revokedTtl, MarkerType.FAMILY);
        removeFamily(sessionId);
        removeAccountSession(sessionId);
    }

    private void removeAccountSession(String sessionId) {
        StoredAccountSession removed = accountSessions.remove(sessionId);
        if (removed == null) {
            subjectSessionIds.values().forEach(ids -> ids.remove(sessionId));
            subjectSessionIds.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            return;
        }
        AccountSubject subject = subject(removed.session().kind(), removed.session().subjectId());
        Set<String> ids = subjectSessionIds.get(subject);
        if (ids != null) {
            ids.remove(sessionId);
            if (ids.isEmpty()) {
                subjectSessionIds.remove(subject, ids);
            }
        }
    }

    private String oldestSessionId(Set<String> ids) {
        return new HashSet<>(ids).stream()
                .filter(accountSessions::containsKey)
                .min(Comparator
                        .comparing((String sessionId) ->
                                accountSessions.get(sessionId).session().loginAt())
                        .thenComparing(sessionId -> sessionId))
                .orElse(null);
    }

    private AccountSubject subject(TokenKind kind, Long subjectId) {
        if (kind == null) {
            throw new IllegalArgumentException("Token kind is required");
        }
        if (subjectId == null) {
            throw new IllegalArgumentException("Subject ID is required");
        }
        return new AccountSubject(kind, subjectId);
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

    private record StoredAccountSession(AccountSession session, Instant expiresAt) {
    }

    private record AccountSubject(TokenKind kind, Long subjectId) {
    }

    private enum MarkerType {
        FAMILY,
        GENERATION
    }

    private record MarkerExpiry(MarkerType type, String id, Instant expiresAt)
            implements Comparable<MarkerExpiry> {

        @Override
        public int compareTo(MarkerExpiry other) {
            return expiresAt.compareTo(other.expiresAt);
        }
    }
}

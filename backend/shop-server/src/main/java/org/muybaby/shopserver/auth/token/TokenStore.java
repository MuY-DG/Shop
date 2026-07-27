package org.muybaby.shopserver.auth.token;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TokenStore {
    boolean saveFamily(String sessionId, List<TokenGrant> grants);

    boolean saveRegisteredFamily(
            AccountSession accountSession,
            int maxSessions,
            List<TokenGrant> grants,
            Duration revokedTtl
    );

    Optional<TokenSession> find(String key);

    Optional<TokenSession> consumeRefreshAndRevokeFamily(String refreshKey, Duration revokedTtl);

    void revokeSession(String sessionId, Duration revokedTtl);

    List<AccountSession> listSessions(TokenKind kind, Long subjectId);

    boolean revokeSubjectSession(
            TokenKind kind,
            Long subjectId,
            String sessionId,
            Duration revokedTtl
    );

    int revokeSubjectSessions(TokenKind kind, Long subjectId, Duration revokedTtl);

    int trimSubjectSessions(TokenKind kind, Long subjectId, int maxSessions, Duration revokedTtl);

    boolean renewSession(String sessionId, TokenKind kind, Duration ttl);

    boolean touchSession(String sessionId, TokenKind kind, Instant lastSeenAt);

    boolean isSessionRevoked(String sessionId);

    boolean isGenerationRevoked(String generationId);
}

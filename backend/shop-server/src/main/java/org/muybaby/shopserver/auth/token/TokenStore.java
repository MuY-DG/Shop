package org.muybaby.shopserver.auth.token;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface TokenStore {
    boolean saveFamily(String sessionId, List<TokenGrant> grants);

    Optional<TokenSession> find(String key);

    Optional<TokenSession> consumeRefreshAndRevokeFamily(String refreshKey, Duration revokedTtl);

    void revokeSession(String sessionId, Duration revokedTtl);

    boolean isSessionRevoked(String sessionId);

    boolean isGenerationRevoked(String generationId);
}

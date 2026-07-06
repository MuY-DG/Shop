package org.muybaby.shopserver.auth.token;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TokenSession(
        String sessionId,
        TokenKind kind,
        Long subjectId,
        String subjectName,
        List<String> roles,
        List<String> permissions,
        Instant issuedAt
) {
    public TokenSession {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public static TokenSession admin(Long userId, String username, List<String> roles, List<String> permissions, Instant issuedAt) {
        return new TokenSession(UUID.randomUUID().toString(), TokenKind.ADMIN, userId, username, roles, permissions, issuedAt);
    }

    public static TokenSession app(Long userId, String openidMasked, Instant issuedAt) {
        return new TokenSession(UUID.randomUUID().toString(), TokenKind.APP, userId, openidMasked, List.of(), List.of(), issuedAt);
    }
}

package org.muybaby.shopserver.auth.token;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public record TokenSession(
        String sessionId,
        String generationId,
        TokenKind kind,
        Long subjectId,
        String subjectName,
        List<String> roles,
        List<String> permissions,
        Instant issuedAt
) {
    private static final Pattern CANONICAL_GENERATION_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    public TokenSession {
        generationId = generationId != null && CANONICAL_GENERATION_ID.matcher(generationId).matches()
                ? generationId
                : sessionId;
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public TokenSession(
            String sessionId,
            TokenKind kind,
            Long subjectId,
            String subjectName,
            List<String> roles,
            List<String> permissions,
            Instant issuedAt
    ) {
        this(sessionId, sessionId, kind, subjectId, subjectName, roles, permissions, issuedAt);
    }

    public static TokenSession admin(Long userId, String username, List<String> roles, List<String> permissions, Instant issuedAt) {
        return new TokenSession(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                TokenKind.ADMIN,
                userId,
                username,
                roles,
                permissions,
                issuedAt
        );
    }

    public static TokenSession app(Long userId, String openidMasked, Instant issuedAt) {
        return app(UUID.randomUUID().toString(), userId, openidMasked, issuedAt);
    }

    public static TokenSession app(String sessionId, Long userId, String openidMasked, Instant issuedAt) {
        return new TokenSession(
                sessionId,
                UUID.randomUUID().toString(),
                TokenKind.APP,
                userId,
                openidMasked,
                List.of(),
                List.of(),
                issuedAt
        );
    }
}

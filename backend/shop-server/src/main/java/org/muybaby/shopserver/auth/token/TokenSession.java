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
        long authVersion,
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
            String generationId,
            TokenKind kind,
            Long subjectId,
            String subjectName,
            List<String> roles,
            List<String> permissions,
            Instant issuedAt
    ) {
        this(sessionId, generationId, kind, subjectId, subjectName, roles, permissions, 0L, issuedAt);
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
        this(sessionId, sessionId, kind, subjectId, subjectName, roles, permissions, 0L, issuedAt);
    }

    public static TokenSession admin(Long userId, String username, List<String> roles, List<String> permissions, Instant issuedAt) {
        return admin(userId, username, roles, permissions, 0L, issuedAt);
    }

    public static TokenSession admin(
            Long userId,
            String username,
            List<String> roles,
            List<String> permissions,
            long authVersion,
            Instant issuedAt
    ) {
        return admin(UUID.randomUUID().toString(), userId, username, roles, permissions, authVersion, issuedAt);
    }

    public static TokenSession admin(
            String sessionId,
            Long userId,
            String username,
            List<String> roles,
            List<String> permissions,
            Instant issuedAt
    ) {
        return admin(sessionId, userId, username, roles, permissions, 0L, issuedAt);
    }

    public static TokenSession admin(
            String sessionId,
            Long userId,
            String username,
            List<String> roles,
            List<String> permissions,
            long authVersion,
            Instant issuedAt
    ) {
        return new TokenSession(
                sessionId,
                UUID.randomUUID().toString(),
                TokenKind.ADMIN,
                userId,
                username,
                roles,
                permissions,
                authVersion,
                issuedAt
        );
    }

    public static TokenSession app(Long userId, String openidMasked, Instant issuedAt) {
        return app(userId, openidMasked, 0L, issuedAt);
    }

    public static TokenSession app(
            Long userId,
            String openidMasked,
            long authVersion,
            Instant issuedAt
    ) {
        return app(UUID.randomUUID().toString(), userId, openidMasked, authVersion, issuedAt);
    }

    public static TokenSession app(String sessionId, Long userId, String openidMasked, Instant issuedAt) {
        return app(sessionId, userId, openidMasked, 0L, issuedAt);
    }

    public static TokenSession app(
            String sessionId,
            Long userId,
            String openidMasked,
            long authVersion,
            Instant issuedAt
    ) {
        return new TokenSession(
                sessionId,
                UUID.randomUUID().toString(),
                TokenKind.APP,
                userId,
                openidMasked,
                List.of(),
                List.of(),
                authVersion,
                issuedAt
        );
    }
}

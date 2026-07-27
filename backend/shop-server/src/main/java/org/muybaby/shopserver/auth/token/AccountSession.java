package org.muybaby.shopserver.auth.token;

import java.time.Instant;
import java.util.Objects;

/**
 * Stable account-session metadata. Unlike {@link TokenSession}, this record describes the
 * whole login session and is retained while refresh token generations are rotated.
 */
public record AccountSession(
        String sessionId,
        TokenKind kind,
        Long subjectId,
        String subjectName,
        String deviceId,
        String ipAddress,
        String userAgent,
        Instant loginAt,
        Instant lastSeenAt
) {
    public AccountSession {
        sessionId = requireText(sessionId, "Session ID is required");
        kind = Objects.requireNonNull(kind, "Token kind is required");
        subjectId = Objects.requireNonNull(subjectId, "Subject ID is required");
        subjectName = normalize(subjectName);
        deviceId = normalize(deviceId);
        ipAddress = normalize(ipAddress);
        userAgent = normalize(userAgent);
        loginAt = Objects.requireNonNull(loginAt, "Login time is required");
        lastSeenAt = lastSeenAt == null ? loginAt : lastSeenAt;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}

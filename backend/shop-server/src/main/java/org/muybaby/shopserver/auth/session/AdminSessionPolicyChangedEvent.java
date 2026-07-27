package org.muybaby.shopserver.auth.session;

public record AdminSessionPolicyChangedEvent(
        Long userId,
        boolean revokeAll,
        int maxSessions
) {
}

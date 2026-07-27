package org.muybaby.shopserver.auth.dto;

import java.time.Instant;

public record AdminSessionResponse(
        String sessionId,
        String deviceName,
        String browser,
        String os,
        String ipAddress,
        String userAgent,
        Instant loginAt,
        Instant lastSeenAt,
        boolean current
) {
}

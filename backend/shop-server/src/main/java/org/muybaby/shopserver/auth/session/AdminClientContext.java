package org.muybaby.shopserver.auth.session;

public record AdminClientContext(
        String deviceId,
        String ipAddress,
        String userAgent
) {
}

package org.muybaby.shopserver.storage.provider;

import java.time.Instant;
import java.util.Map;

public record DirectUploadGrant(
        String uploadUrl,
        Map<String, String> formData,
        Instant expiresAt
) {
}

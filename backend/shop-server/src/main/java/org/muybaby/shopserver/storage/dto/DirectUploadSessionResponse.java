package org.muybaby.shopserver.storage.dto;

import java.time.Instant;
import java.util.Map;

public record DirectUploadSessionResponse(
        String uploadId,
        String uploadUrl,
        Map<String, String> formData,
        Instant expiresAt
) {
}

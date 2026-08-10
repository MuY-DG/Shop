package org.muybaby.shopserver.auth.dto;

import java.time.LocalDateTime;

public record AdminRegistrationStatusResponse(
        boolean enabled,
        LocalDateTime updatedAt
) {
}

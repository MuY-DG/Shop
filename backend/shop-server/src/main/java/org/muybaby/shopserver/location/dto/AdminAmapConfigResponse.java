package org.muybaby.shopserver.location.dto;

import java.time.LocalDateTime;

public record AdminAmapConfigResponse(
        boolean enabled,
        boolean keyConfigured,
        String miniProgramKeyMasked,
        LocalDateTime updatedAt
) {
}

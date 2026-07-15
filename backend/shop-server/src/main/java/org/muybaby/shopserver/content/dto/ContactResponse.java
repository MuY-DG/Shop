package org.muybaby.shopserver.content.dto;

import java.time.LocalDateTime;

public record ContactResponse(
        String phone,
        LocalDateTime updatedAt
) {
}

package org.muybaby.shopserver.user.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminCustomerStatusResponse(
        @JsonStringId Long userId,
        String status,
        LocalDateTime updatedAt
) {
}

package org.muybaby.shopserver.user.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminCustomerResponse(
        @JsonStringId Long id,
        String nickname,
        String phoneNumber,
        Boolean phoneAuthorized,
        String status,
        Integer couponTotalCount,
        Integer couponAvailableCount,
        Integer couponUsedCount,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

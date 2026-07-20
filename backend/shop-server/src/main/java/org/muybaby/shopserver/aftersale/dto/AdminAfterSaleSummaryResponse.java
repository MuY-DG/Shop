package org.muybaby.shopserver.aftersale.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminAfterSaleSummaryResponse(
        Long id,
        Long orderId,
        String orderNo,
        @JsonStringId Long userId,
        String userNickname,
        String afterSaleType,
        String status,
        String reason,
        Long requestedAmountCent,
        LocalDateTime createdAt
) {
}

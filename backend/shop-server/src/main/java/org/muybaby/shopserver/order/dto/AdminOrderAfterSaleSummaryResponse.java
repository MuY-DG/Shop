package org.muybaby.shopserver.order.dto;

import java.time.LocalDateTime;

public record AdminOrderAfterSaleSummaryResponse(
        Long afterSaleId,
        String afterSaleNo,
        String afterSaleType,
        String status,
        Long requestedAmountCent,
        LocalDateTime createdAt
) {
}

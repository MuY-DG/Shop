package org.muybaby.shopserver.order.dto;

import java.time.LocalDateTime;

public record OrderStatusLogResponse(
        Long id,
        Long orderId,
        Long afterSaleId,
        String fromStatus,
        String toStatus,
        String eventType,
        String operatorType,
        Long operatorId,
        String description,
        LocalDateTime createdAt
) {
}

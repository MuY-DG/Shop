package org.muybaby.shopserver.order.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record OrderStatusLogResponse(
        Long id,
        Long orderId,
        Long afterSaleId,
        String fromStatus,
        String toStatus,
        String eventType,
        String operatorType,
        @JsonStringId Long operatorId,
        String description,
        LocalDateTime createdAt
) {
}

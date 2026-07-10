package org.muybaby.shopserver.order.dto;

import java.time.LocalDateTime;

public record OrderReceiptResponse(
        Long orderId,
        String status,
        LocalDateTime completedAt
) {
}

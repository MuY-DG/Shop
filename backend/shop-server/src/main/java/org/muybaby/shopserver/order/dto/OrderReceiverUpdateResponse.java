package org.muybaby.shopserver.order.dto;

import java.time.LocalDateTime;

public record OrderReceiverUpdateResponse(
        Long orderId,
        String status,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        LocalDateTime updatedAt
) {
}

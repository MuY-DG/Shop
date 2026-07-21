package org.muybaby.shopserver.product.engagement.dto;

import java.time.LocalDateTime;

public record ReviewableOrderItemResponse(
        Long orderItemId,
        Long orderId,
        String orderNo,
        Long skuId,
        String skuSpecText,
        LocalDateTime completedAt
) {
}

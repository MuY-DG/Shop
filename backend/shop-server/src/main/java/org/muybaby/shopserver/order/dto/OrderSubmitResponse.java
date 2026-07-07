package org.muybaby.shopserver.order.dto;

import java.time.LocalDateTime;

public record OrderSubmitResponse(
        Long orderId,
        String orderNo,
        String status,
        Long payableAmountCent,
        Long couponDiscountCent,
        LocalDateTime createdAt
) {
}

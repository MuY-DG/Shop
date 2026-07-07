package org.muybaby.shopserver.order.dto;

import java.time.LocalDateTime;

public record OrderSummaryResponse(
        Long orderId,
        String orderNo,
        String status,
        Long productAmountCent,
        Long couponDiscountCent,
        Long freightCent,
        Long payableAmountCent,
        Long paidAmountCent,
        String productTitle,
        Integer itemCount,
        LocalDateTime createdAt
) {
}

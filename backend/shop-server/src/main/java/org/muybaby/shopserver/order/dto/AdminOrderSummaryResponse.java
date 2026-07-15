package org.muybaby.shopserver.order.dto;

import java.time.LocalDateTime;

public record AdminOrderSummaryResponse(
        Long orderId,
        String orderNo,
        String status,
        Long productAmountCent,
        Long couponDiscountCent,
        Long freightCent,
        Long payableAmountCent,
        Long paidAmountCent,
        String receiverName,
        String receiverPhone,
        String productTitle,
        String productSubtitle,
        String mainImage,
        String skuImage,
        String displayImage,
        String specText,
        Integer firstItemQuantity,
        Integer itemCount,
        LocalDateTime createdAt
) {
}

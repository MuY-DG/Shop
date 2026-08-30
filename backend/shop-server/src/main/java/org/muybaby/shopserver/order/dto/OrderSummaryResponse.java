package org.muybaby.shopserver.order.dto;

import java.time.LocalDateTime;
import java.util.List;

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
        List<OrderSummaryItemResponse> items,
        Integer pendingReviewCount,
        AppOrderAfterSaleSummaryResponse latestAfterSale,
        LocalDateTime createdAt
) {
    public OrderSummaryResponse {
        items = items == null ? List.of() : List.copyOf(items);
        pendingReviewCount = pendingReviewCount == null ? 0 : pendingReviewCount;
    }
}

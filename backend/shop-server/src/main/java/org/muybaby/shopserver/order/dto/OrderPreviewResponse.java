package org.muybaby.shopserver.order.dto;

import java.util.List;

public record OrderPreviewResponse(
        List<OrderPreviewItemResponse> items,
        Long productOriginalAmountCent,
        Long productAmountCent,
        Long userCouponId,
        String couponName,
        Long couponDiscountCent,
        Long freightCent,
        Long payableAmountCent
) {
}

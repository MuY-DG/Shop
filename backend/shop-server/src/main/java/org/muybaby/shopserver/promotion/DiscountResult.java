package org.muybaby.shopserver.promotion;

public record DiscountResult(
        Long userCouponId,
        Boolean available,
        Long discountAmountCent,
        String unavailableReason
) {
}

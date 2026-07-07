package org.muybaby.shopserver.coupon.dto;

import java.time.LocalDateTime;

public record AvailableCouponItemResponse(
        Long userCouponId,
        Long templateId,
        String name,
        String couponType,
        Long thresholdCent,
        Long discountCent,
        Long discountAmountCent,
        Boolean available,
        String unavailableReason,
        LocalDateTime validEndAt
) {
}

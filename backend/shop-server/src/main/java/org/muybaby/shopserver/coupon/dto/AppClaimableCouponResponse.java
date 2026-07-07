package org.muybaby.shopserver.coupon.dto;

import java.time.LocalDateTime;

public record AppClaimableCouponResponse(
        Long templateId,
        String name,
        String description,
        String couponType,
        Long thresholdCent,
        Long discountCent,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        Integer claimedCount,
        Integer perUserLimit,
        Boolean claimable,
        String unavailableReason
) {
}

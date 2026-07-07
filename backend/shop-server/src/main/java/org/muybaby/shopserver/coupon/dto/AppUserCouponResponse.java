package org.muybaby.shopserver.coupon.dto;

import java.time.LocalDateTime;

public record AppUserCouponResponse(
        Long userCouponId,
        Long templateId,
        String name,
        String couponType,
        Long thresholdCent,
        Long discountCent,
        String scopeType,
        String status,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        LocalDateTime claimedAt
) {
}

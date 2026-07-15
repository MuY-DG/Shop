package org.muybaby.shopserver.user.dto;

import java.time.LocalDateTime;

public record AdminIssuableCouponTemplateResponse(
        Long id,
        String name,
        String description,
        String couponType,
        String discountType,
        Long thresholdCent,
        Long discountCent,
        String scopeType,
        String scopeValue,
        Integer stockRemaining,
        Integer perUserLimit,
        Integer userClaimCount,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt
) {
}

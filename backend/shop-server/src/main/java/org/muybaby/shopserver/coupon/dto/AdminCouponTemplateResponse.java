package org.muybaby.shopserver.coupon.dto;

import java.time.LocalDateTime;

public record AdminCouponTemplateResponse(
        Long id,
        String name,
        String description,
        String couponType,
        String discountType,
        Long thresholdCent,
        Long discountCent,
        String scopeType,
        String scopeValue,
        String strategyKey,
        Integer totalStock,
        Integer claimedCount,
        Integer stockRemaining,
        Integer perUserLimit,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        String status,
        Integer sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String distributionMode,
        Long audienceUserId,
        String audienceNickname,
        String audiencePhoneNumber
) {
}

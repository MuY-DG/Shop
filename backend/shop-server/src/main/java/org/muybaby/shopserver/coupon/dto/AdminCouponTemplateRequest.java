package org.muybaby.shopserver.coupon.dto;

import java.time.LocalDateTime;

public record AdminCouponTemplateRequest(
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
        Integer perUserLimit,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        String status,
        Integer sortOrder
) {
}

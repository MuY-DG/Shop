package org.muybaby.shopserver.promotion;

public record CouponCandidate(
        Long userCouponId,
        Long templateId,
        String name,
        String couponType,
        String discountType,
        Long thresholdCent,
        Long discountCent,
        String scopeType,
        String scopeValue
) {
}

package org.muybaby.shopserver.coupon.dto;

import java.time.LocalDateTime;

public record AdminCouponClaimResponse(
        Long id,
        Long templateId,
        String templateName,
        String distributionMode,
        Long userId,
        String userNickname,
        String userPhoneNumber,
        Long userCouponId,
        String couponType,
        String discountType,
        Long thresholdCent,
        Long discountCent,
        String scopeType,
        String scopeValue,
        String status,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        Long usedOrderId,
        LocalDateTime usedAt,
        String issueSource,
        Long operatorAdminUserId,
        String operatorDisplayName,
        String issueNote,
        LocalDateTime claimedAt
) {
}

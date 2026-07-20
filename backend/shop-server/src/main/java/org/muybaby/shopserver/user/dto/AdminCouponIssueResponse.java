package org.muybaby.shopserver.user.dto;

import java.time.LocalDateTime;

public record AdminCouponIssueResponse(
        Long userCouponId,
        Long templateId,
        String templateName,
        String status,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        LocalDateTime issuedAt
) {
}

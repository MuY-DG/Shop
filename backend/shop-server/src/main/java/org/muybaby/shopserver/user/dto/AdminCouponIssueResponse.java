package org.muybaby.shopserver.user.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminCouponIssueResponse(
        @JsonStringId Long userCouponId,
        Long templateId,
        String templateName,
        String status,
        LocalDateTime validStartAt,
        LocalDateTime validEndAt,
        LocalDateTime issuedAt
) {
}

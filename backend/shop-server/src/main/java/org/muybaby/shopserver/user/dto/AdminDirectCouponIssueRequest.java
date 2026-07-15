package org.muybaby.shopserver.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record AdminDirectCouponIssueRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 255) String description,
        @NotBlank String couponType,
        @NotNull @PositiveOrZero Long thresholdCent,
        @NotNull @Positive Long discountCent,
        @NotNull LocalDateTime validStartAt,
        @NotNull LocalDateTime validEndAt,
        @Size(max = 200) String note
) {
}

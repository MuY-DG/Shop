package org.muybaby.shopserver.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AdminCouponIssueRequest(
        @NotNull @Positive Long templateId,
        @Size(max = 200) String note
) {
}

package org.muybaby.shopserver.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AppOrderSubmitRequest(
        List<Long> cartItemIds,
        Long userCouponId,
        @NotBlank @Size(max = 80) String idempotencyKey
) {
}

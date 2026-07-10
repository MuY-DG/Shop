package org.muybaby.shopserver.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.muybaby.shopserver.order.CheckoutSource;

import java.util.List;

public record AppOrderSubmitRequest(
        CheckoutSource source,
        List<Long> cartItemIds,
        Long skuId,
        Integer quantity,
        @NotNull Long addressId,
        Long userCouponId,
        @NotBlank @Size(max = 80) String idempotencyKey
) {
}

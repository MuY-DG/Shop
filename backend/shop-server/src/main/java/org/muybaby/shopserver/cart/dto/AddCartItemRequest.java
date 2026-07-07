package org.muybaby.shopserver.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddCartItemRequest(
        @NotNull Long skuId,
        @NotNull @Min(1) @Max(999) Integer quantity
) {
}

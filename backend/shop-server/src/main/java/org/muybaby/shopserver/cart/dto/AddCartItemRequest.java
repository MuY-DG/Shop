package org.muybaby.shopserver.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddCartItemRequest(
        @NotNull Long skuId,
        @NotNull @Min(1) @Max(999) Integer quantity,
        String analyticsVisitorId,
        String analyticsSessionId,
        String analyticsEntryScene
) {
    public AddCartItemRequest(Long skuId, Integer quantity) {
        this(skuId, quantity, null, null, null);
    }
}

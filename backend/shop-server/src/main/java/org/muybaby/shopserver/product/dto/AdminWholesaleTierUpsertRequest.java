package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdminWholesaleTierUpsertRequest(
        @NotNull @Min(2) @Max(999) Integer minQuantity,
        @NotNull @Min(1) Long unitPriceCent
) {
}

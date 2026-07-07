package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminSkuUpsertRequest(
        Long id,
        @NotBlank String skuCode,
        @NotBlank String specJson,
        @NotBlank String specText,
        @NotNull @Min(1) Long priceCent,
        @NotNull @Min(0) Long originalPriceCent,
        @NotNull @Min(0) Integer stockAvailable,
        @NotNull @Min(0) Integer weightGram,
        String image,
        @NotBlank String status,
        @NotNull @Min(0) Integer sortOrder
) {
}

package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminStockAdjustmentRequest(
        @NotNull Integer quantityDelta,
        @NotBlank @Size(max = 255) String reason
) {
}

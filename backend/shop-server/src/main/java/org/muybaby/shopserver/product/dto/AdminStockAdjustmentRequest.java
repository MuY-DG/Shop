package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminStockAdjustmentRequest(
        Integer quantityDelta,
        @NotBlank String reason
) {
}

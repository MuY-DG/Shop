package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdminLowStockThresholdRequest(
        @NotNull @Min(0) Integer lowStockThreshold
) {
}

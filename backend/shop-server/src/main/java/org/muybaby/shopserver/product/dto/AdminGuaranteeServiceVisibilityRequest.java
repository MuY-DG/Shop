package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.NotNull;

public record AdminGuaranteeServiceVisibilityRequest(
        @NotNull Boolean visible
) {
}

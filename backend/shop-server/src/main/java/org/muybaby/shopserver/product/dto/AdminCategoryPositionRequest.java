package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AdminCategoryPositionRequest(
        @NotNull @Min(0) Long parentId,
        @NotNull @Min(0) Integer index
) {
}

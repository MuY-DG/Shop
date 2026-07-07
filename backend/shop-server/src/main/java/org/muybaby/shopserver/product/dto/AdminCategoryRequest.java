package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminCategoryRequest(
        @NotNull Long parentId,
        @NotBlank String name,
        String icon,
        @NotNull @Min(0) Integer sortOrder,
        @NotBlank String status
) {
}

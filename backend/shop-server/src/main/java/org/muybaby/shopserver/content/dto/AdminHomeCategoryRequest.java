package org.muybaby.shopserver.content.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminHomeCategoryRequest(
        @NotNull Long categoryId,
        @NotNull Long imageFileId,
        @Min(0) Integer sortOrder,
        @NotBlank String status
) {
}

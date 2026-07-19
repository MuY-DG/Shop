package org.muybaby.shopserver.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AdminProductParameterDefinitionRequest(
        @NotBlank @Size(max = 64) String parameterCode,
        @NotBlank @Size(max = 64) String parameterName,
        @NotBlank String valueType,
        @Size(max = 24) String unit,
        @Size(max = 255) String description,
        @NotNull Boolean required,
        @NotNull Boolean filterable,
        @NotNull Boolean cardVisible,
        @NotNull Boolean detailVisible,
        @Min(0) Integer sortOrder,
        @NotBlank String status,
        List<Long> categoryIds,
        @Valid List<AdminProductParameterOptionRequest> options
) {
}

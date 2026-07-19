package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminProductParameterOptionRequest(
        Long id,
        @NotBlank @Size(max = 64) String optionCode,
        @NotBlank @Size(max = 64) String optionLabel,
        @Min(0) Integer displayLevel,
        @Min(0) Integer sortOrder
) {
}

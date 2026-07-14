package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record AdminSpecTemplateValueRequest(
        Long id,
        @Size(max = 64) String valueKey,
        @NotBlank @Size(max = 30) String valueName,
        @Min(0) Integer sortOrder
) {
}

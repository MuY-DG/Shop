package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminGuaranteeServiceRequest(
        @NotBlank @Size(max = 64) String termsName,
        @NotBlank @Size(max = 500) String contentDescription,
        @NotBlank @Size(max = 500) String icon,
        Long iconFileId,
        @Min(0) Integer sortOrder,
        @NotNull Boolean visible
) {
}

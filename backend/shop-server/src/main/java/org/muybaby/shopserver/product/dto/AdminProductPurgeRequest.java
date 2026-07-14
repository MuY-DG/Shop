package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminProductPurgeRequest(
        @NotBlank @Size(max = 128) String confirmationTitle
) {
}

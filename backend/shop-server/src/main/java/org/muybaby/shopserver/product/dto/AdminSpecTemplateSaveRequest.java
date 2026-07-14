package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminSpecTemplateSaveRequest(
        @NotBlank @Size(max = 64) String name
) {
}

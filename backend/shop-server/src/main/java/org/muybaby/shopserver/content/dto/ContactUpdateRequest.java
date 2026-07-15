package org.muybaby.shopserver.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContactUpdateRequest(
        @NotBlank @Size(max = 32) String phone
) {
}

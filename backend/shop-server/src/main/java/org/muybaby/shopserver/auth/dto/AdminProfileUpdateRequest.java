package org.muybaby.shopserver.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminProfileUpdateRequest(
        @NotBlank @Size(max = 64) String displayName,
        @Email @Size(max = 128) String email
) {
}

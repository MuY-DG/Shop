package org.muybaby.shopserver.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminLoginRequest(
        @NotBlank @Size(max = 64) String userName,
        @NotBlank @Size(max = 128) String password
) {
}

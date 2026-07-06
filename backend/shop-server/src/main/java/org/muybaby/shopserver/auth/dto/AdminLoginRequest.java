package org.muybaby.shopserver.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminLoginRequest(
        @NotBlank String userName,
        @NotBlank String password
) {
}

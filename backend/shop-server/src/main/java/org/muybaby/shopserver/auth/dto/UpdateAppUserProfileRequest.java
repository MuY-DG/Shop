package org.muybaby.shopserver.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAppUserProfileRequest(
        @NotBlank String nickname
) {
}

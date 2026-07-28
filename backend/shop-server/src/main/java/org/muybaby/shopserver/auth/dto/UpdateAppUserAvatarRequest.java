package org.muybaby.shopserver.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateAppUserAvatarRequest(
        @NotBlank @Size(max = 1024) String avatarUrl
) {
}

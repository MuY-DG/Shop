package org.muybaby.shopserver.auth.dto;

import jakarta.validation.constraints.NotNull;

public record AdminRegistrationSettingUpdateRequest(
        @NotNull Boolean enabled
) {
}

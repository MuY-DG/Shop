package org.muybaby.shopserver.auth.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.util.List;

public record CurrentAdminUserResponse(
        @JsonStringId
        Long userId,
        String userName,
        String email,
        String avatar,
        List<String> roles,
        List<String> buttons
) {
    public CurrentAdminUserResponse {
        roles = roles == null ? List.of() : List.copyOf(roles);
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }
}

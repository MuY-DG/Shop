package org.muybaby.shopserver.auth.dto;

import java.util.List;

public record CurrentAdminUserResponse(
        Long userId,
        String userName,
        String displayName,
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

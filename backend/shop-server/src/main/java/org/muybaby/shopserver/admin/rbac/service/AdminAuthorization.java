package org.muybaby.shopserver.admin.rbac.service;

import java.util.List;

public record AdminAuthorization(
        Long userId,
        String username,
        List<String> roles,
        List<String> permissions
) {
    public AdminAuthorization {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}

package org.muybaby.shopserver.admin.rbac.dto;

import java.time.LocalDateTime;

public record AdminRoleResponse(
        Long id,
        String code,
        String name,
        String description,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

package org.muybaby.shopserver.admin.rbac.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminUserResponse(
        Long id,
        String username,
        String displayName,
        String email,
        String avatar,
        String status,
        List<Long> roleIds,
        List<String> roleCodes,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

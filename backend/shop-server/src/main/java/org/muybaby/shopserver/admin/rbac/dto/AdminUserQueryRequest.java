package org.muybaby.shopserver.admin.rbac.dto;

public record AdminUserQueryRequest(
        Long current,
        Long size,
        String username,
        String email,
        String status
) {
}

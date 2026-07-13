package org.muybaby.shopserver.admin.rbac.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AdminRoleGrantRequest(
        @NotNull List<Long> menuIds,
        @NotNull List<Long> permissionIds
) {
}

package org.muybaby.shopserver.admin.rbac.dto;

import java.util.List;

public record AdminRoleGrantResponse(
        Long roleId,
        List<Long> menuIds,
        List<Long> permissionIds
) {
}

package org.muybaby.shopserver.admin.rbac.dto;

import java.util.List;

public record AdminRouteResponse(
        Long id,
        String name,
        String path,
        String component,
        AdminRouteMetaResponse meta,
        List<AdminRouteResponse> children
) {
    public AdminRouteResponse {
        children = children == null ? List.of() : List.copyOf(children);
    }
}

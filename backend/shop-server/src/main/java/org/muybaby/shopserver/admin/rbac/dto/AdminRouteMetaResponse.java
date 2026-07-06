package org.muybaby.shopserver.admin.rbac.dto;

import java.util.List;

public record AdminRouteMetaResponse(
        String title,
        String icon,
        boolean keepAlive,
        List<AdminRouteAuthResponse> authList
) {
}

package org.muybaby.shopserver.admin.rbac.dto;

import java.util.List;

public record AdminRouteMetaResponse(
        String title,
        String icon,
        boolean keepAlive,
        boolean isFullPage,
        List<AdminRouteAuthResponse> authList
) {
    public AdminRouteMetaResponse(
            String title,
            String icon,
            boolean keepAlive,
            List<AdminRouteAuthResponse> authList
    ) {
        this(title, icon, keepAlive, false, authList);
    }

    public AdminRouteMetaResponse {
        authList = authList == null ? List.of() : List.copyOf(authList);
    }
}

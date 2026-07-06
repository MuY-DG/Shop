package org.muybaby.shopserver.admin.rbac;

import org.muybaby.shopserver.admin.rbac.dto.AdminRouteResponse;
import org.muybaby.shopserver.admin.rbac.service.AdminMenuRouteService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/system")
public class AdminMenuController {

    private final AdminMenuRouteService menuRouteService;

    public AdminMenuController(AdminMenuRouteService menuRouteService) {
        this.menuRouteService = menuRouteService;
    }

    @GetMapping("/menus")
    public ApiResponse<List<AdminRouteResponse>> menus(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success(menuRouteService.routesForUser(principal.subjectId()));
    }
}

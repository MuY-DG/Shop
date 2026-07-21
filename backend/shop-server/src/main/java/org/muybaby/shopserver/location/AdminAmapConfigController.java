package org.muybaby.shopserver.location;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.location.config.AmapRuntimeConfigService;
import org.muybaby.shopserver.location.dto.AdminAmapConfigRequest;
import org.muybaby.shopserver.location.dto.AdminAmapConfigResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/amap/config")
public class AdminAmapConfigController {

    private final AmapRuntimeConfigService configService;

    public AdminAmapConfigController(AmapRuntimeConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('amap:config:read')")
    public ApiResponse<AdminAmapConfigResponse> current() {
        return ApiResponse.success(configService.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('amap:config:write')")
    public ApiResponse<AdminAmapConfigResponse> update(@RequestBody AdminAmapConfigRequest request) {
        return ApiResponse.success(configService.update(request));
    }
}

package org.muybaby.shopserver.wechat.platform;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.wechat.platform.dto.AdminWechatPlatformConfigResponse;
import org.muybaby.shopserver.wechat.platform.dto.AdminWechatPlatformConfigUpdateRequest;
import org.muybaby.shopserver.wechat.platform.dto.AdminWechatPlatformEnvironmentImportRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/wechat/platform-config")
public class AdminWechatPlatformConfigController {

    private final WechatPlatformConfigService configService;

    public AdminWechatPlatformConfigController(WechatPlatformConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('wechat-platform:config:read', 'wechat-platform:config:write')")
    public ApiResponse<AdminWechatPlatformConfigResponse> current() {
        return ApiResponse.success(configService.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('wechat-platform:config:write')")
    public ApiResponse<AdminWechatPlatformConfigResponse> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestBody AdminWechatPlatformConfigUpdateRequest request
    ) {
        return ApiResponse.success(configService.update(request, principal.subjectId()));
    }

    @PostMapping("/legacy-env-import")
    @PreAuthorize("hasAuthority('wechat-platform:config:write')")
    public ApiResponse<AdminWechatPlatformConfigResponse> importLegacyEnvironment(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestBody AdminWechatPlatformEnvironmentImportRequest request
    ) {
        return ApiResponse.success(
                configService.importLegacyEnvironment(request, principal.subjectId()));
    }
}

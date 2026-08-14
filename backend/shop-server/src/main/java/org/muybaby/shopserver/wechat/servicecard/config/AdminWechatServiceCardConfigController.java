package org.muybaby.shopserver.wechat.servicecard.config;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.wechat.servicecard.config.dto.AdminWechatServiceCardConfigResponse;
import org.muybaby.shopserver.wechat.servicecard.config.dto.AdminWechatServiceCardConfigUpdateRequest;
import org.muybaby.shopserver.wechat.servicecard.config.dto.AdminWechatServiceCardEnvironmentImportRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/wechat-service-cards/config")
public class AdminWechatServiceCardConfigController {

    private final WechatServiceCardConfigService configService;

    public AdminWechatServiceCardConfigController(WechatServiceCardConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('wechat-service-card:config:read', "
            + "'wechat-service-card:config:write')")
    public ApiResponse<AdminWechatServiceCardConfigResponse> current() {
        return ApiResponse.success(configService.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('wechat-service-card:config:write')")
    public ApiResponse<AdminWechatServiceCardConfigResponse> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestBody AdminWechatServiceCardConfigUpdateRequest request
    ) {
        return ApiResponse.success(configService.update(request, principal.subjectId()));
    }

    @PostMapping("/legacy-env-import")
    @PreAuthorize("hasAuthority('wechat-service-card:config:write')")
    public ApiResponse<AdminWechatServiceCardConfigResponse> importLegacyEnvironment(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestBody AdminWechatServiceCardEnvironmentImportRequest request
    ) {
        return ApiResponse.success(
                configService.importLegacyEnvironment(request, principal.subjectId()));
    }
}

package org.muybaby.shopserver.logistics.waybill.config;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/logistics/wechat-express/config")
public class AdminWechatExpressConfigController {

    private final WechatExpressConfigService configService;

    public AdminWechatExpressConfigController(WechatExpressConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('logistics:express:config:read')")
    public ApiResponse<WechatExpressConfigResponse> current() {
        return ApiResponse.success(configService.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('logistics:express:config:write')")
    public ApiResponse<WechatExpressConfigResponse> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody WechatExpressConfigUpdateRequest request
    ) {
        return ApiResponse.success(configService.update(request, principal.subjectId()));
    }
}

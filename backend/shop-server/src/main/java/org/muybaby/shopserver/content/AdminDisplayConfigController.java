package org.muybaby.shopserver.content;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.content.dto.AdminDisplayConfigResponse;
import org.muybaby.shopserver.content.dto.DisplayConfigUpdateRequest;
import org.muybaby.shopserver.content.service.DisplayConfigService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/display-config")
public class AdminDisplayConfigController {
    private final DisplayConfigService service;

    public AdminDisplayConfigController(DisplayConfigService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('wechat-platform:config:read', 'wechat-platform:config:write')")
    public ApiResponse<AdminDisplayConfigResponse> current() {
        return ApiResponse.success(service.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('wechat-platform:config:write')")
    public ApiResponse<AdminDisplayConfigResponse> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestBody DisplayConfigUpdateRequest request
    ) {
        return ApiResponse.success(service.update(request, principal.subjectId()));
    }
}

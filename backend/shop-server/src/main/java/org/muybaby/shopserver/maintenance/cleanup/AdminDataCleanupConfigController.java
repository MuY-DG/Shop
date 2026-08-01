package org.muybaby.shopserver.maintenance.cleanup;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupConfigResponse;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupConfigUpdateRequest;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/data-cleanup/config")
public class AdminDataCleanupConfigController {

    private final DataCleanupConfigService configService;

    public AdminDataCleanupConfigController(DataCleanupConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('data-cleanup:config:read', 'data-cleanup:config:write')")
    public ApiResponse<DataCleanupConfigResponse> current() {
        return ApiResponse.success(configService.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('data-cleanup:config:write')")
    public ApiResponse<DataCleanupConfigResponse> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody DataCleanupConfigUpdateRequest request
    ) {
        return ApiResponse.success(configService.update(request, principal.subjectId()));
    }
}

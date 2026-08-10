package org.muybaby.shopserver.auth;

import jakarta.validation.Valid;
import org.muybaby.shopserver.auth.dto.AdminRegistrationSettingUpdateRequest;
import org.muybaby.shopserver.auth.dto.AdminRegistrationStatusResponse;
import org.muybaby.shopserver.auth.service.AdminRegistrationService;
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
@RequestMapping("/admin/system/registration")
@PreAuthorize("hasRole('SUPER')")
public class AdminRegistrationSettingController {

    private final AdminRegistrationService adminRegistrationService;

    public AdminRegistrationSettingController(AdminRegistrationService adminRegistrationService) {
        this.adminRegistrationService = adminRegistrationService;
    }

    @GetMapping
    public ApiResponse<AdminRegistrationStatusResponse> current() {
        return ApiResponse.success(adminRegistrationService.currentSetting());
    }

    @PutMapping
    public ApiResponse<AdminRegistrationStatusResponse> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AdminRegistrationSettingUpdateRequest request
    ) {
        return ApiResponse.success(adminRegistrationService.updateSetting(
                principal.subjectId(),
                request.enabled()
        ));
    }
}

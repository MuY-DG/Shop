package org.muybaby.shopserver.user;

import org.muybaby.shopserver.auth.dto.AppUserProfile;
import org.muybaby.shopserver.auth.dto.UpdateAppUserProfileRequest;
import org.muybaby.shopserver.auth.service.AppAuthService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/users")
public class AppUserController {

    private final AppAuthService appAuthService;

    public AppUserController(AppAuthService appAuthService) {
        this.appAuthService = appAuthService;
    }

    @GetMapping("/me")
    public ApiResponse<AppUserProfile> me(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success(appAuthService.me(principal));
    }

    @PutMapping("/me")
    public ApiResponse<AppUserProfile> updateMe(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody UpdateAppUserProfileRequest request
    ) {
        return ApiResponse.success(appAuthService.updateProfile(principal, request));
    }
}

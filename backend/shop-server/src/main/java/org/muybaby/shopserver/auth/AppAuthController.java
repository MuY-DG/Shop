package org.muybaby.shopserver.auth;

import jakarta.validation.Valid;
import org.muybaby.shopserver.auth.dto.AppLoginRequest;
import org.muybaby.shopserver.auth.dto.AppSessionResponse;
import org.muybaby.shopserver.auth.dto.AppUserProfile;
import org.muybaby.shopserver.auth.dto.PhoneAuthorizeRequest;
import org.muybaby.shopserver.auth.dto.RefreshTokenRequest;
import org.muybaby.shopserver.auth.service.AppAuthService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/auth")
public class AppAuthController {

    private final AppAuthService appAuthService;

    public AppAuthController(AppAuthService appAuthService) {
        this.appAuthService = appAuthService;
    }

    @PostMapping("/login")
    public ApiResponse<AppSessionResponse> login(@Valid @RequestBody AppLoginRequest request) {
        return ApiResponse.success(appAuthService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<AppSessionResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(appAuthService.refresh(request));
    }

    @PostMapping("/phone")
    public ApiResponse<AppUserProfile> authorizePhone(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody PhoneAuthorizeRequest request
    ) {
        return ApiResponse.success(appAuthService.authorizePhone(principal, request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        appAuthService.logout(principal);
        return ApiResponse.success();
    }
}

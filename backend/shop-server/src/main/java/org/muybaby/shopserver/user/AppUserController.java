package org.muybaby.shopserver.user;

import org.muybaby.shopserver.auth.dto.AppUserProfile;
import org.muybaby.shopserver.auth.service.AppAuthService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
}

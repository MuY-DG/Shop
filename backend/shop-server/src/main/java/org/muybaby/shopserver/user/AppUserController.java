package org.muybaby.shopserver.user;

import jakarta.validation.Valid;
import org.muybaby.shopserver.auth.dto.AppUserProfile;
import org.muybaby.shopserver.auth.dto.AppUserAvatarUpdateResponse;
import org.muybaby.shopserver.auth.dto.UpdateAppUserAvatarRequest;
import org.muybaby.shopserver.auth.dto.UpdateAppUserProfileRequest;
import org.muybaby.shopserver.auth.service.AppAuthService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.user.service.AppUserAvatarService;
import org.muybaby.shopserver.user.service.AppUserAvatarRateLimiter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/app/users")
public class AppUserController {

    private final AppAuthService appAuthService;
    private final AppUserAvatarService appUserAvatarService;

    public AppUserController(AppAuthService appAuthService, AppUserAvatarService appUserAvatarService) {
        this.appAuthService = appAuthService;
        this.appUserAvatarService = appUserAvatarService;
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

    @PostMapping("/me/avatar")
    public ResponseEntity<ApiResponse<AppUserAvatarUpdateResponse>> updateAvatar(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam("file") MultipartFile file
    ) {
        return avatarResponse(appUserAvatarService.updateAvatar(principal, file));
    }

    @PutMapping("/me/avatar")
    public ResponseEntity<ApiResponse<AppUserAvatarUpdateResponse>> updateAvatar(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody UpdateAppUserAvatarRequest request
    ) {
        return avatarResponse(
                appUserAvatarService.updateAvatar(principal, request.avatarUrl()));
    }

    private ResponseEntity<ApiResponse<AppUserAvatarUpdateResponse>> avatarResponse(
            AppUserAvatarService.AvatarUpdateResult result
    ) {
        return ResponseEntity.ok()
                .header(
                        "X-RateLimit-Limit",
                        Integer.toString(AppUserAvatarRateLimiter.DAILY_LIMIT)
                )
                .header(
                        "X-RateLimit-Remaining",
                        Integer.toString(result.remainingChanges())
                )
                .body(ApiResponse.success(AppUserAvatarUpdateResponse.from(
                        result.profile(),
                        result.remainingChanges()
                )));
    }
}

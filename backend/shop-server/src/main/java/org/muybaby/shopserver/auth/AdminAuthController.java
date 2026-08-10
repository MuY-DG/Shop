package org.muybaby.shopserver.auth;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.muybaby.shopserver.auth.dto.AdminLoginRequest;
import org.muybaby.shopserver.auth.dto.AdminPasswordChangeRequest;
import org.muybaby.shopserver.auth.dto.AdminProfileUpdateRequest;
import org.muybaby.shopserver.auth.dto.AdminRegistrationAvailabilityResponse;
import org.muybaby.shopserver.auth.dto.AdminRegistrationRequest;
import org.muybaby.shopserver.auth.dto.AdminSessionResponse;
import org.muybaby.shopserver.auth.dto.CurrentAdminUserResponse;
import org.muybaby.shopserver.auth.dto.LoginTokenResponse;
import org.muybaby.shopserver.auth.dto.RefreshTokenRequest;
import org.muybaby.shopserver.auth.session.AdminClientContextResolver;
import org.muybaby.shopserver.auth.session.AdminSessionManagementService;
import org.muybaby.shopserver.auth.service.AdminAuthService;
import org.muybaby.shopserver.auth.service.AdminLoginResult;
import org.muybaby.shopserver.auth.service.AdminRegistrationService;
import org.muybaby.shopserver.auth.service.AdminSelfService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.web.RequestLogContext;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;
    private final AdminClientContextResolver adminClientContextResolver;
    private final AdminSessionManagementService adminSessionManagementService;
    private final AdminRegistrationService adminRegistrationService;
    private final AdminSelfService adminSelfService;

    public AdminAuthController(
            AdminAuthService adminAuthService,
            AdminClientContextResolver adminClientContextResolver,
            AdminSessionManagementService adminSessionManagementService,
            AdminRegistrationService adminRegistrationService,
            AdminSelfService adminSelfService
    ) {
        this.adminAuthService = adminAuthService;
        this.adminClientContextResolver = adminClientContextResolver;
        this.adminSessionManagementService = adminSessionManagementService;
        this.adminRegistrationService = adminRegistrationService;
        this.adminSelfService = adminSelfService;
    }

    @GetMapping("/registration")
    public ApiResponse<AdminRegistrationAvailabilityResponse> registrationAvailability() {
        return ApiResponse.success(adminRegistrationService.availability());
    }

    @PostMapping("/register")
    public ApiResponse<Long> register(@Valid @RequestBody AdminRegistrationRequest request) {
        return ApiResponse.success(adminRegistrationService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginTokenResponse> login(
            @Valid @RequestBody AdminLoginRequest request,
            HttpServletRequest servletRequest
    ) {
        String username = request.userName().strip();
        RequestLogContext.markLoginCandidate(servletRequest, username);
        AdminLoginResult result = adminAuthService.login(
                request,
                adminClientContextResolver.resolve(servletRequest)
        );
        RequestLogContext.markLoginSuccess(
                servletRequest,
                result.adminUserId(),
                result.username()
        );
        return ApiResponse.success(result.tokens());
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(adminAuthService.refresh(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        adminAuthService.logout(principal);
        return ApiResponse.success();
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AdminSessionResponse>> sessions(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(adminSessionManagementService.sessions(
                principal.subjectId(),
                principal.sessionId()
        ));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> revokeSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable String sessionId
    ) {
        adminSessionManagementService.revokeSession(principal.subjectId(), sessionId);
        return ApiResponse.success();
    }

    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        adminSessionManagementService.revokeAllSessions(principal.subjectId());
        return ApiResponse.success();
    }

    @GetMapping("/current-user")
    public ApiResponse<CurrentAdminUserResponse> currentUser(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(adminAuthService.currentUser(principal));
    }

    @PutMapping("/profile")
    public ApiResponse<CurrentAdminUserResponse> updateProfile(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AdminProfileUpdateRequest request
    ) {
        adminSelfService.updateProfile(principal.subjectId(), request);
        return ApiResponse.success(adminAuthService.currentUser(principal));
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AdminPasswordChangeRequest request
    ) {
        adminSelfService.changePassword(principal.subjectId(), request);
        return ApiResponse.success();
    }
}

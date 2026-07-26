package org.muybaby.shopserver.auth;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.muybaby.shopserver.auth.dto.AdminLoginRequest;
import org.muybaby.shopserver.auth.dto.CurrentAdminUserResponse;
import org.muybaby.shopserver.auth.dto.LoginTokenResponse;
import org.muybaby.shopserver.auth.dto.RefreshTokenRequest;
import org.muybaby.shopserver.auth.service.AdminAuthService;
import org.muybaby.shopserver.auth.service.AdminLoginResult;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.web.RequestLogContext;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.security.web.ClientIpResolver;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;
    private final ClientIpResolver clientIpResolver;

    public AdminAuthController(AdminAuthService adminAuthService, ClientIpResolver clientIpResolver) {
        this.adminAuthService = adminAuthService;
        this.clientIpResolver = clientIpResolver;
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
                clientIpResolver.resolve(servletRequest)
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

    @GetMapping("/current-user")
    public ApiResponse<CurrentAdminUserResponse> currentUser(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(adminAuthService.currentUser(principal));
    }
}
